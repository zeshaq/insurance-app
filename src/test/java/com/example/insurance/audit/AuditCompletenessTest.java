package com.example.insurance.audit;

import com.example.insurance.claim.Claim;
import com.example.insurance.claim.ClaimEventPublisher;
import com.example.insurance.claim.ClaimRepository;
import com.example.insurance.claim.ClaimService;
import com.example.insurance.claim.MinioStorageService;
import com.example.insurance.claim.OcrInvoker;
import com.example.insurance.claim.OcrRequest;
import com.example.insurance.claim.OcrResponse;
import com.example.insurance.claim.PartnerInvoker;
import com.example.insurance.claim.PartnerResponse;
import com.example.insurance.dashboard.AgentDashboardPublisher;
import com.example.insurance.payment.IdempotencyStore;
import com.example.insurance.payment.PaymentEventPublisher;
import com.example.insurance.payment.PaymentGatewayChargeRequest;
import com.example.insurance.payment.PaymentGatewayInvoker;
import com.example.insurance.payment.PaymentGatewayResponse;
import com.example.insurance.payment.PaymentRepository;
import com.example.insurance.payment.PaymentRequest;
import com.example.insurance.payment.PaymentService;
import com.example.insurance.payment.PaymentDlqPublisher;
import com.example.insurance.policy.PolicyPublisher;
import com.example.insurance.policy.PolicyRepository;
import com.example.insurance.policy.PolicyService;
import com.example.insurance.policy.Redlock;
import com.example.insurance.quote.CreditBureauClient;
import com.example.insurance.quote.CreditScoreCache;
import com.example.insurance.quote.Quote;
import com.example.insurance.quote.QuoteCache;
import com.example.insurance.quote.QuotePublisher;
import com.example.insurance.quote.QuoteRepository;
import com.example.insurance.quote.QuoteRequest;
import com.example.insurance.quote.QuoteService;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.Persistence;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Audit-trail completeness regression — issue #32.
 *
 * Every state-changing domain operation MUST emit an {@link AuditEvent} to
 * the compacted {@code audit-events} topic. The contract is informal in
 * docs/qa-roadmap.md; this test pins it: each operation is exercised
 * end-to-end against real Postgres + Redis + Kafka via Testcontainers,
 * then a fresh consumer (independent group.id so we never collide with
 * the production {@link AuditConsumer}) drains the topic and asserts a
 * matching record was produced.
 *
 * Each operation is its own {@code @Test} on purpose: when a regression
 * removes an audit.publish(...) call, the failure surface names the
 * specific operation that regressed — "policy_bind_emits_audit FAILED:
 * Expected an audit record with key 'policy:POL-...' on topic
 * 'audit-events' but none arrived within 10s" — instead of a single
 * monolithic assertion that says "something didn't emit."
 *
 * Why @TestInstance(PER_CLASS) and @TestMethodOrder: the containers and
 * service wiring are heavyweight (~30s to boot); we set them up once and
 * let every test share the topic. Order is preserved so a developer
 * eyeballing the output sees the same operation list as the spec.
 *
 * The test does NOT assert anything about message shape beyond
 * entityType + entityId + action — that contract belongs to the unit
 * tests in {@link AuditContrastTest}. This test exists for one job:
 * prove the publish was called.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditCompletenessTest {

    /** Max wall-clock we'll wait for an audit record to appear after the operation returns. */
    private static final Duration AUDIT_POLL_BUDGET = Duration.ofSeconds(10);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("insurance")
            .withUsername("insurance")
            .withPassword("insurance");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    private EntityManagerFactory                    emf;
    private EntityManager                           em;
    private RedisClient                             redisClient;
    private StatefulRedisConnection<String, String> redisConn;
    private RedisCommands<String, String>           redis;

    private TestAuditPublisher audit;
    private KafkaConsumer<String, String> auditConsumer;

    private QuoteService    quoteService;
    private PolicyService   policyService;
    private PaymentService  paymentService;
    private ClaimService    claimService;

    private Long   firstQuoteId;
    private String firstPolicyNumber;
    private Long   firstClaimId;

    @BeforeAll
    void bootstrap() throws Exception {
        // 1. Flyway migrates the test DB to the same schema production uses.
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        // 2. JPA EMF against the Testcontainers Postgres.
        Map<String, Object> props = new HashMap<>();
        props.put("jakarta.persistence.jdbc.url",      POSTGRES.getJdbcUrl());
        props.put("jakarta.persistence.jdbc.user",     POSTGRES.getUsername());
        props.put("jakarta.persistence.jdbc.password", POSTGRES.getPassword());
        emf = Persistence.createEntityManagerFactory("insurance-it", props);
        em  = emf.createEntityManager();

        // 3. Real Lettuce against the Redis container.
        String redisUri = "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
        redisClient = RedisClient.create(redisUri);
        redisConn   = redisClient.connect();
        redis       = redisConn.sync();

        // 4. Wire a real AuditPublisher pointed at the Testcontainers Kafka.
        //    The production AuditPublisher hard-codes kafka:9092 in @PostConstruct;
        //    TestAuditPublisher overrides init() to point at KAFKA.getBootstrapServers().
        audit = new TestAuditPublisher(KAFKA.getBootstrapServers());
        audit.initForTest();

        // 5. Independent consumer with a fresh group.id — must NOT share with
        //    AuditConsumer (which itself randomizes its group). We want our own
        //    earliest read so we see every record produced by the operations.
        Properties cprops = new Properties();
        cprops.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        cprops.put(ConsumerConfig.GROUP_ID_CONFIG,          "audit-completeness-test-" + UUID.randomUUID());
        cprops.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        cprops.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        cprops.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class.getName());
        cprops.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        auditConsumer = new KafkaConsumer<>(cprops);
        auditConsumer.subscribe(List.of(AuditPublisher.TOPIC));
        // Drain partition assignment + position to "now" so the consumer is
        // ready to see records published from this point on.
        auditConsumer.poll(Duration.ofMillis(500));

        // 6. Wire each service the same way QuoteRoundTripIT does: real
        //    repositories backed by the JPA EMF, real publishers/clients
        //    where they fit, no-op stubs where Liberty would normally
        //    supply the wiring (mpReactiveMessaging emitters, Liberty
        //    JTA, RestClient proxies).
        wireQuoteService();
        wirePolicyService();
        wirePaymentService();
        wireClaimService();
    }

    @AfterAll
    void teardown() {
        try { if (auditConsumer != null) auditConsumer.close(); } catch (Exception ignore) {}
        try { audit.close();                                    } catch (Exception ignore) {}
        try { if (em != null)           em.close();             } catch (Exception ignore) {}
        try { if (emf != null)          emf.close();            } catch (Exception ignore) {}
        try { if (redisConn != null)    redisConn.close();      } catch (Exception ignore) {}
        try { if (redisClient != null)  redisClient.shutdown(); } catch (Exception ignore) {}
    }

    // -----------------------------------------------------------------
    //  Tests, one per state-changing operation
    // -----------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("QuoteService.createQuote MUST emit audit-events {entityType=quote, action=CALCULATED}")
    void quote_calculate_emits_audit() {
        QuoteRequest req = new QuoteRequest("1HGBH41JXMN109186", 35, "STANDARD");

        EntityTransaction tx = em.getTransaction();
        tx.begin();
        Quote saved;
        try {
            saved = quoteService.createQuote(req);
            tx.commit();
        } catch (RuntimeException e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        }
        firstQuoteId = saved.getId();

        // The contract: after createQuote returns, an audit record with
        // entityType=quote, entityId=<savedId>, action=CALCULATED must be
        // visible on audit-events within the poll budget.
        AuditEvent ev = pollFor("quote", String.valueOf(saved.getId()));
        if (ev == null) {
            fail("MISSING AUDIT EMISSION — QuoteService.createQuote(id=" + saved.getId() + ") "
               + "did not publish to audit-events. Expected entityType='quote' entityId='"
               + saved.getId() + "' action='CALCULATED'. "
               + "Fix: inject AuditPublisher into QuoteService and call "
               + "audit.publish(new AuditEvent(\"quote\", String.valueOf(saved.getId()), "
               + "\"CALCULATED\", ...)) before returning. "
               + "See ClaimService.publishToAuditAndClaimEvents for the canonical shape.");
        }
        assertThat(ev.action())
                .as("Quote audit action should be CALCULATED (the only state quote transitions to today)")
                .isEqualTo("CALCULATED");
    }

    @Test
    @Order(2)
    @DisplayName("PolicyService.bind MUST emit audit-events {entityType=policy, action=BOUND}")
    void policy_bind_emits_audit() {
        // bind needs an existing Quote — reuse the one the quote test wrote;
        // if quote_calculate_emits_audit was skipped or failed prematurely,
        // create a quote here so we still exercise bind.
        if (firstQuoteId == null) {
            EntityTransaction tx = em.getTransaction();
            tx.begin();
            try {
                firstQuoteId = quoteService.createQuote(
                        new QuoteRequest("WBA3A5C50DF601234", 40, "BASIC")).getId();
                tx.commit();
            } catch (RuntimeException e) {
                if (tx.isActive()) tx.rollback();
                throw e;
            }
        }

        EntityTransaction tx = em.getTransaction();
        tx.begin();
        PolicyService.BindResult result;
        try {
            result = policyService.bind(firstQuoteId);
            tx.commit();
        } catch (RuntimeException e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        }
        firstPolicyNumber = result.policy().getPolicyNumber();

        AuditEvent ev = pollFor("policy", firstPolicyNumber);
        if (ev == null) {
            fail("MISSING AUDIT EMISSION — PolicyService.bind(quoteId=" + firstQuoteId + ") "
               + "did not publish to audit-events. Expected entityType='policy' entityId='"
               + firstPolicyNumber + "' action='BOUND'. "
               + "Fix: inject AuditPublisher into PolicyService and call audit.publish(...) "
               + "inside the bind() lock-protected block after publisher.publishStateChange(saved). "
               + "Today only the keyed policy-events topic captures the bind; audit-events "
               + "(the compacted current-state projection) is silently skipped.");
        }
        assertThat(ev.action()).isEqualTo("BOUND");
    }

    @Test
    @Order(3)
    @DisplayName("PaymentService.process (success) MUST emit audit-events {entityType=payment, action=SUCCEEDED}")
    void payment_process_emits_audit() throws Exception {
        if (firstPolicyNumber == null) {
            fail("Test sequence broken: payment requires a bound policy and "
               + "policy_bind_emits_audit didn't populate firstPolicyNumber.");
        }
        String idemKey = "audit-test-" + UUID.randomUUID();

        // PaymentService.process orchestrates two internal @Transactional
        // saves (savePending, saveSuccess). Under RESOURCE_LOCAL those
        // annotations are no-ops, so we open a single tx around the whole
        // process() call and commit at the end. The DB ends up with one
        // row in payment, status=SUCCEEDED.
        PaymentService.Result r = inTx(() -> paymentService.process(
                idemKey,
                new PaymentRequest(firstPolicyNumber, new BigDecimal("123.45"), "USD")));
        assertThat(r.payment().getStatus())
                .as("Payment must succeed before we can assert audit emission of SUCCEEDED")
                .isEqualTo("SUCCEEDED");

        AuditEvent ev = pollFor("payment", String.valueOf(r.payment().getId()));
        if (ev == null) {
            fail("MISSING AUDIT EMISSION — PaymentService.process(idemKey=" + idemKey + ", "
               + "policy=" + firstPolicyNumber + ") succeeded but did not publish to "
               + "audit-events. Expected entityType='payment' entityId='" + r.payment().getId()
               + "' action='SUCCEEDED'. "
               + "Fix: inject AuditPublisher into PaymentService and call audit.publish(...) "
               + "immediately after eventPublisher.publish(ok) in the success branch "
               + "(and also next to the FAILED branch for completeness). "
               + "Today only payment-events captures the outcome; audit-events is silently skipped.");
        }
        assertThat(ev.action()).isEqualTo("SUCCEEDED");
    }

    @Test
    @Order(4)
    @DisplayName("ClaimService.file MUST emit audit-events {entityType=claim, action=FILED}")
    void claim_file_emits_audit() throws Exception {
        if (firstPolicyNumber == null) {
            fail("Test sequence broken: claim requires a bound policy and "
               + "policy_bind_emits_audit didn't populate firstPolicyNumber.");
        }

        // No multipart content — content==null skips the Minio upload branch in
        // ClaimService.file, so we never need a real Minio container.
        Claim filed = inTx(() -> claimService.file(
                firstPolicyNumber,
                "Audit-completeness probe filing",
                /* content */         null,
                /* contentLength */   0,
                /* contentType */     null,
                /* originalName */    null,
                /* otherPartyVin */   null));
        firstClaimId = filed.getId();

        AuditEvent ev = pollFor("claim", String.valueOf(filed.getId()));
        if (ev == null) {
            fail("MISSING AUDIT EMISSION — ClaimService.file(policy=" + firstPolicyNumber + ") "
               + "did not publish to audit-events. Expected entityType='claim' entityId='"
               + filed.getId() + "' action='FILED'. "
               + "Regression site: ClaimService.publishToAuditAndClaimEvents — verify "
               + "audit.publish is still being called for the FILED transition.");
        }
        assertThat(ev.action()).isEqualTo("FILED");
    }

    @Test
    @Order(5)
    @DisplayName("ClaimService.approve MUST emit audit-events {entityType=claim, action=APPROVED}")
    void claim_approve_emits_audit() throws Exception {
        if (firstClaimId == null) {
            fail("Test sequence broken: approve requires a filed claim and "
               + "claim_file_emits_audit didn't populate firstClaimId.");
        }

        // Drain whatever audit-events records were produced before this call
        // so the next poll starts from "after approve()". Without this drain
        // pollFor would find the FILED record from the previous test (same
        // key claim:<id>) and report success based on the wrong action.
        drainAuditTopic();

        Claim approved = inTx(() -> claimService.approve(firstClaimId));
        assertThat(approved.getStatus()).isEqualTo("APPROVED");

        AuditEvent ev = pollFor("claim", String.valueOf(firstClaimId));
        if (ev == null) {
            fail("MISSING AUDIT EMISSION — ClaimService.approve(id=" + firstClaimId + ") "
               + "did not publish to audit-events. Expected entityType='claim' entityId='"
               + firstClaimId + "' action='APPROVED'. "
               + "Regression site: ClaimService.publishToAuditAndClaimEvents — verify "
               + "audit.publish is still being called for the APPROVED transition.");
        }
        assertThat(ev.action())
                .as("After draining FILED, the next record for claim:%d must be APPROVED", firstClaimId)
                .isEqualTo("APPROVED");
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    /**
     * Polls {@code audit-events} until a record matching the given key
     * appears, or {@link #AUDIT_POLL_BUDGET} elapses. Returns the parsed
     * AuditEvent, or null if the budget ran out. The compaction key shape
     * is {@code entityType:entityId} — same as {@link AuditEvent#key()}.
     */
    private AuditEvent pollFor(String entityType, String entityId) {
        String expectedKey = entityType + ":" + entityId;
        long deadline = System.nanoTime() + AUDIT_POLL_BUDGET.toNanos();
        var jsonb = jakarta.json.bind.JsonbBuilder.create();
        try {
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> recs = auditConsumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : recs) {
                    if (expectedKey.equals(r.key())) {
                        try {
                            return jsonb.fromJson(r.value(), AuditEvent.class);
                        } catch (Exception ex) {
                            fail("audit-events record at key=" + expectedKey
                                    + " was unparseable JSON: " + ex.getMessage()
                                    + " — raw=" + r.value());
                        }
                    }
                }
            }
            return null;
        } finally {
            try { jsonb.close(); } catch (Exception ignore) {}
        }
    }

    /** Reads to end-of-topic and discards — used between two operations on the same key. */
    private void drainAuditTopic() {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> recs = auditConsumer.poll(Duration.ofMillis(300));
            if (recs.isEmpty()) return;
        }
    }

    // -----------------------------------------------------------------
    //  Service wiring (mirrors QuoteRoundTripIT's reflective injection)
    // -----------------------------------------------------------------

    private void wireQuoteService() {
        QuoteRepository repo = new QuoteRepository();
        setField(repo, "em", em);

        QuoteCache cache = new QuoteCache();
        setField(cache, "redis", redis);

        CreditScoreCache creditCache = new CreditScoreCache();
        setField(creditCache, "redis", redis);

        // Liberty would supply mpReactiveMessaging's Emitter; standalone
        // JUnit has no such infrastructure, so we no-op the publish. The
        // audit assertion is the only Kafka write this test cares about.
        QuotePublisher publisher = new QuotePublisher() {
            @Override
            public void publishCalculated(Quote q) { /* no-op */ }
        };

        // No credit bureau in-test — null reply forces the production fallback.
        CreditBureauClient bureau = vin -> null;

        quoteService = new QuoteService();
        setField(quoteService, "repo",         repo);
        setField(quoteService, "cache",        cache);
        setField(quoteService, "publisher",    publisher);
        setField(quoteService, "creditBureau", bureau);
        setField(quoteService, "creditCache",  creditCache);

        // Phase-6 forward-compat: if QuoteService grows an `audit` field
        // (issue #32 follow-up), inject our test AuditPublisher into it
        // automatically. Reflective lookup avoids a compile error today
        // when the field doesn't exist yet.
        injectIfPresent(quoteService, "audit", audit);
    }

    private void wirePolicyService() {
        PolicyRepository policyRepo = new PolicyRepository();
        setField(policyRepo, "em", em);

        QuoteRepository quoteRepo = new QuoteRepository();
        setField(quoteRepo, "em", em);

        Redlock redlock = new Redlock();
        setField(redlock, "redis", redis);

        // PolicyPublisher writes to a real Kafka broker; point its producer
        // at the Testcontainers broker by reflectively swapping the field.
        PolicyPublisher publisher = new PolicyPublisher();
        setField(publisher, "producer", buildTestProducer("policy-events-test"));

        policyService = new PolicyService();
        setField(policyService, "repo",      policyRepo);
        setField(policyService, "quoteRepo", quoteRepo);
        setField(policyService, "redlock",   redlock);
        setField(policyService, "publisher", publisher);

        injectIfPresent(policyService, "audit", audit);
    }

    private void wirePaymentService() {
        PaymentRepository repo = new PaymentRepository();
        setField(repo, "em", em);

        PolicyRepository policyRepo = new PolicyRepository();
        setField(policyRepo, "em", em);

        IdempotencyStore idem = new IdempotencyStore();
        setField(idem, "redis", redis);

        // Gateway always succeeds in this test — we're verifying the
        // happy-path audit emission, not the DLQ branch.
        PaymentGatewayInvoker gw = new PaymentGatewayInvoker() {
            @Override
            public PaymentGatewayResponse charge(PaymentGatewayChargeRequest req) {
                PaymentGatewayResponse ok = new PaymentGatewayResponse();
                ok.setExternalRef("ext-audit-test-" + UUID.randomUUID());
                ok.setStatus("SUCCEEDED");
                return ok;
            }
        };

        PaymentEventPublisher eventPublisher = new PaymentEventPublisher();
        setField(eventPublisher, "producer", buildTestProducer("payment-events-test"));

        PaymentDlqPublisher dlq = new PaymentDlqPublisher();
        setField(dlq, "producer", buildTestProducer("payment-dlq-test"));

        paymentService = new PaymentService();
        setField(paymentService, "repo",            repo);
        setField(paymentService, "policyRepo",      policyRepo);
        setField(paymentService, "dlq",             dlq);
        setField(paymentService, "idem",            idem);
        setField(paymentService, "gatewayInvoker",  gw);
        setField(paymentService, "eventPublisher", eventPublisher);

        injectIfPresent(paymentService, "audit", audit);
    }

    private void wireClaimService() {
        ClaimRepository repo = new ClaimRepository();
        setField(repo, "em", em);

        PolicyRepository policyRepo = new PolicyRepository();
        setField(policyRepo, "em", em);

        // Storage is unused (we always pass content=null in the test) but
        // ClaimService.@Inject demands a non-null bean. Subclass with a
        // throw-on-call body so a future test that exercises a binary
        // upload path lights up loudly here.
        MinioStorageService storage = new MinioStorageService() {
            @Override
            public String upload(java.io.InputStream content, long contentLength,
                                 String contentType, String originalName) throws Exception {
                throw new UnsupportedOperationException(
                        "Audit-completeness test should never upload — pass content=null");
            }
        };

        OcrInvoker ocr = new OcrInvoker() {
            @Override
            public OcrResponse extract(OcrRequest req) { return null; }
        };

        PartnerInvoker partner = new PartnerInvoker() {
            @Override
            public PartnerResponse lookup(String vin) { return null; }
        };

        // Dashboard publish goes to Redis pub/sub; our real Redis is fine
        // with publishes that nobody's subscribed to, so let it run.
        AgentDashboardPublisher dashboard = new AgentDashboardPublisher();
        setField(dashboard, "redis", redis);

        ClaimEventPublisher claimEvents = new ClaimEventPublisher();
        setField(claimEvents, "producer", buildTestProducer("claim-events-test"));

        claimService = new ClaimService();
        setField(claimService, "repo",        repo);
        setField(claimService, "policyRepo",  policyRepo);
        setField(claimService, "storage",     storage);
        setField(claimService, "ocr",         ocr);
        setField(claimService, "partner",     partner);
        setField(claimService, "dashboard",   dashboard);
        setField(claimService, "audit",       audit);
        setField(claimService, "claimEvents", claimEvents);

        // Need a transactional save through bare JPA; the @Transactional
        // annotation in ClaimService is a no-op outside a JTA container,
        // so wrap each save in an EntityTransaction by intercepting at
        // the test level. ClaimService.file() invokes saveFiled / saveOcr
        // / savePartner which call repo.save — repo.save itself is
        // @Transactional but with RESOURCE_LOCAL we have to begin/commit
        // manually around the whole file() call. The test wraps it.
        //
        // (Implementation note: the wrapping happens inside the test
        // method, not here. This comment documents the contract for any
        // future test that calls claimService directly.)
    }

    /** Build a Kafka producer wired to the Testcontainers broker for stubbed publishers. */
    private KafkaProducer<String, String> buildTestProducer(String clientId) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.CLIENT_ID_CONFIG, clientId);
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaProducer<>(p);
    }

    /** {@link AuditPublisher} subclass that bootstraps against a caller-supplied broker. */
    static class TestAuditPublisher extends AuditPublisher {

        private final String bootstrap;

        TestAuditPublisher(String bootstrap) {
            this.bootstrap = bootstrap;
        }

        void initForTest() throws Exception {
            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class.getName());
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.CLIENT_ID_CONFIG, "audit-completeness-test");
            props.put(ProducerConfig.ACKS_CONFIG, "all");
            KafkaProducer<String, String> p = new KafkaProducer<>(props);
            // The 'producer' field on AuditPublisher is private; reflect.
            Field f = AuditPublisher.class.getDeclaredField("producer");
            f.setAccessible(true);
            f.set(this, p);
        }

        void close() {
            try {
                Field f = AuditPublisher.class.getDeclaredField("producer");
                f.setAccessible(true);
                Object p = f.get(this);
                if (p != null) ((KafkaProducer<?, ?>) p).close();
            } catch (Exception ignore) { /* best-effort */ }
        }
    }

    /** Reflectively set a private field on a target. Same pattern as QuoteRoundTripIT. */
    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to inject " + name + " on " + target, e);
        }
    }

    /**
     * Inject the field iff it exists — used for forward-compat with
     * services that don't yet have an `audit` field. When the gap is
     * fixed and the field appears, this auto-wires it without a code
     * change here.
     */
    private static void injectIfPresent(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (NoSuchFieldException nsf) {
            // No such field today; the audit assertion will fail loudly
            // for that operation. That's the right signal.
        } catch (Exception e) {
            throw new IllegalStateException("Failed optional inject of " + name + " on " + target, e);
        }
    }

    /**
     * Wraps a body in a manual RESOURCE_LOCAL transaction. Mirrors what
     * Liberty's JTA container does in production, so the @Transactional
     * annotations sprinkled across service methods become effectively
     * "open and commit one outer tx around the whole operation."
     */
    private <T> T inTx(TxBody<T> body) throws Exception {
        EntityTransaction tx = em.getTransaction();
        tx.begin();
        try {
            T r = body.run();
            tx.commit();
            return r;
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        }
    }

    /** Functional interface compatible with checked-exception-throwing service calls. */
    @FunctionalInterface
    private interface TxBody<T> {
        T run() throws Exception;
    }
}
