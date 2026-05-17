package com.example.insurance.quote;

import com.example.insurance.config.RedisClientProducer;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.Persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end round-trip for {@link QuoteService} against real Postgres
 * and real Redis via Testcontainers. Issue #8.
 *
 * What's exercised here that the unit test can't:
 *   1. {@code QuoteRepository.save()}'s {@code em.flush()} actually assigns
 *      the IDENTITY id before the cache write. If flush() regresses, the
 *      Redis key written below ends up at {@code quote:null} and the assertion
 *      "key quote:<id> exists" lights up.
 *   2. {@link QuoteCache} serialization round-trip: JSON-B encode → Redis
 *      string → JSON-B decode preserves premium precision and the
 *      OffsetDateTime fields.
 *   3. Flyway → JPA mapping. If a column rename in V*__*.sql lands without
 *      a matching {@code @Column} change, JPA fails before the cache check.
 *
 * Why the JNDI/JTA detour: production uses
 * {@code @PersistenceContext(unitName="insurance")} with a JTA datasource.
 * Standalone JUnit has no JTA + no JNDI, so this IT loads a parallel
 * RESOURCE_LOCAL persistence-unit named "insurance-it" defined in
 * src/test/resources/META-INF/persistence.xml, manages transactions
 * explicitly via {@link EntityTransaction}, and reflectively injects the
 * EM into the repository.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuoteRoundTripIT {

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

    private EntityManagerFactory emf;
    private EntityManager        em;
    private RedisClient                                  redisClient;
    private StatefulRedisConnection<String, String>      redisConn;
    private RedisCommands<String, String>                redis;

    private QuoteService    service;
    private QuoteRepository repo;
    private QuoteCache      cache;

    private final Jsonb jsonb = JsonbBuilder.create();

    @BeforeAll
    void bootstrap() throws Exception {
        // 1. Run the production Flyway migrations against the test DB so the
        //    schema lines up with the @Entity mappings.
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        // 2. Build the JPA EMF with the Testcontainers JDBC coordinates.
        Map<String, Object> props = new HashMap<>();
        props.put("jakarta.persistence.jdbc.url",      POSTGRES.getJdbcUrl());
        props.put("jakarta.persistence.jdbc.user",     POSTGRES.getUsername());
        props.put("jakarta.persistence.jdbc.password", POSTGRES.getPassword());
        emf = Persistence.createEntityManagerFactory("insurance-it", props);
        em  = emf.createEntityManager();

        // 3. Real Lettuce sync client against the Redis container.
        String redisUri = "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
        redisClient = RedisClient.create(redisUri);
        redisConn   = redisClient.connect();
        redis       = redisConn.sync();

        // 4. Wire the production beans by hand. Field injection in JPA / CDI
        //    is what the production code uses, so reflection is the right
        //    knife here.
        repo = new QuoteRepository();
        setField(repo, "em", em);

        cache = new QuoteCache();
        setField(cache, "redis", redis);

        CreditScoreCache creditCache = new CreditScoreCache();
        setField(creditCache, "redis", redis);

        // No Kafka in this IT — replace the publisher with a no-op subclass.
        QuotePublisher publisher = new QuotePublisher() {
            @Override
            public void publishCalculated(Quote q) {
                // intentionally empty; payment-replay IT (follow-up) covers Kafka
            }
        };

        // No credit-bureau in this IT — null lookup forces the production
        // fallback to neutral 1.0.
        CreditBureauClient bureau = vin -> null;

        service = new QuoteService();
        setField(service, "repo",         repo);
        setField(service, "cache",        cache);
        setField(service, "publisher",    publisher);
        setField(service, "creditBureau", bureau);
        setField(service, "creditCache",  creditCache);

        // Sanity check the RedisClientProducer class is reachable from the
        // test classpath — guards against a refactor that moves the cache
        // wiring without updating the test bootstrap.
        assertThat(RedisClientProducer.class.getPackage().getName())
                .isEqualTo("com.example.insurance.config");
    }

    @AfterAll
    void teardown() {
        if (em != null)            em.close();
        if (emf != null)           emf.close();
        if (redisConn != null)     redisConn.close();
        if (redisClient != null)   redisClient.shutdown();
    }

    @Test
    @DisplayName("createQuote(req) writes a Quote row to Postgres AND a quote:<id> entry to Redis")
    void roundTripsThroughPostgresAndRedis() {
        QuoteRequest req = new QuoteRequest("1HGBH41JXMN109186", 35, "STANDARD");

        // The production code's @Transactional is provided by Liberty's
        // JTA container. Standalone RESOURCE_LOCAL needs us to begin/commit
        // explicitly around the call that flushes the entity.
        EntityTransaction tx = em.getTransaction();
        tx.begin();
        Quote saved;
        try {
            saved = service.createQuote(req);
            tx.commit();
        } catch (RuntimeException e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        }

        // 1. The Quote object the service returned has a real id (the
        //    em.flush() ran inside QuoteRepository.save()).
        assertThat(saved.getId())
                .as("If this is null, QuoteRepository.save() lost its em.flush() — "
                  + "and downstream cache + Kafka writes are silently keyed on null.")
                .isNotNull();
        assertThat(saved.getPremium()).isEqualByComparingTo("750.00");

        // 2. JPA row exists with the expected shape (verified via a fresh EM
        //    read, so we're not just trusting first-level cache).
        em.clear();
        Quote reread = em.find(Quote.class, saved.getId());
        assertThat(reread).isNotNull();
        assertThat(reread.getVehicleVin()).isEqualTo("1HGBH41JXMN109186");
        assertThat(reread.getCoverageType()).isEqualTo("STANDARD");
        assertThat(reread.getPremium()).isEqualByComparingTo("750.00");
        assertThat(reread.getStatus()).isEqualTo("CALCULATED");

        // 3. Redis has the exact key "quote:<id>" — the contract documented
        //    in ADR 0005 and in QuoteCache#key. Not "some key exists."
        String expectedKey = "quote:" + saved.getId();
        String cached = redis.get(expectedKey);
        assertThat(cached)
                .as("Expected Redis key %s; if missing, the cache write either "
                  + "ran with a null id (look for 'quote:null' instead) or the "
                  + "key shape regressed.", expectedKey)
                .isNotNull();

        // 4. Negative assertion: nothing was written to the canary "quote:null".
        assertThat(redis.get("quote:null"))
                .as("If this is non-null, em.flush() regressed — the cache "
                  + "wrote the entity before its IDENTITY id was assigned.")
                .isNull();

        // 5. The cached JSON round-trips back into a Quote whose premium
        //    matches the DB row — guards JSON-B precision drift on BigDecimal.
        Quote fromCache = jsonb.fromJson(cached, Quote.class);
        assertThat(fromCache.getId()).isEqualTo(saved.getId());
        assertThat(fromCache.getPremium()).isEqualByComparingTo(reread.getPremium());

        // 6. Higher-level: QuoteService.getById on a cold cache (we delete
        //    the entry first) still works because the read-through path
        //    re-populates it. This exercises the production cache MISS
        //    branch in QuoteService#getById.
        redis.del(expectedKey);
        Quote viaService = service.getById(saved.getId());
        assertThat(viaService).isNotNull();
        assertThat(viaService.getPremium()).isEqualByComparingTo(new BigDecimal("750.00"));
        assertThat(redis.get(expectedKey))
                .as("getById on a miss must re-populate the cache.")
                .isNotNull();
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to inject " + name + " on " + target, e);
        }
    }
}
