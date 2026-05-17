package com.example.insurance.pact;

import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.Pact;
import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;

import kotlin.Pair;

import jakarta.ws.rs.core.HttpHeaders;

import org.apache.hc.core5.http.HttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pact-JVM provider verifier — replays the consumer pacts collected under
 * {@code pacts/} at the repo root against a running Liberty.
 *
 * <p>Wire-up strategy:
 * <ul>
 *   <li>{@code @PactFolder("pacts")} loads every {@code *-liberty.json}
 *       at {@code <project root>/pacts/} — surefire/failsafe run from
 *       the project root, so this is the right relative path.</li>
 *   <li>{@code pact.provider.url} is an optional system property; if
 *       not set, defaults to {@code http://localhost:9080}. On the VM
 *       that's the live Liberty container; in CI a fresh Liberty is
 *       booted by the workflow before this test runs.</li>
 *   <li>The class is only executed when {@code pact.verifier.enabled=true}
 *       so a regular {@code mvn test} doesn't try to verify against a
 *       provider that isn't up.</li>
 *   <li>{@link #attachAuth(HttpRequest)} is annotated
 *       {@link TargetRequestFilter} — Pact-JVM calls it for every replayed
 *       request, letting us inject a real WSO2-IS-minted JWT (fetched from
 *       the provider's own {@code GET /api/auth/token}) and rewrite the
 *       placeholder claim id in the agent-app pact.</li>
 *   <li>State handlers seed and patch as needed. For the
 *       "claim 42 exists" state, we insert a Policy + Claim row, then
 *       set {@link #seededClaimId} so the request filter swaps the
 *       placeholder id from the consumer pact for the actual row's id.</li>
 * </ul>
 *
 * <p>The seeding is best-effort: if the DB isn't reachable the state
 * handler logs and lets the verification proceed; the failing interaction
 * is then a clear "no such claim" error from Liberty, not a silent pass.
 */
@Provider("liberty")
@PactFolder("pacts")
@EnabledIfSystemProperty(named = "pact.verifier.enabled", matches = "true")
public class LibertyProviderPactTest {

    private static final String PROVIDER_URL =
            System.getProperty("pact.provider.url", "http://localhost:9080");

    /** Set by the "claim 42 exists" state; the request filter swaps the URL accordingly. */
    private static volatile Long seededClaimId = 42L;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void setTarget(PactVerificationContext context) {
        URI base = URI.create(PROVIDER_URL);
        int port = base.getPort();
        if (port == -1) port = "https".equals(base.getScheme()) ? 443 : 80;
        // Use an AuthAwareHttpTarget that wraps HttpTestTarget and decorates
        // every prepared request with a Bearer JWT + X-User-Id. Pact-JVM's
        // @TargetRequestFilter discovery in 4.6.x didn't fire for our
        // class (likely a Kotlin-reflection visibility quirk; the state-
        // change discovery worked fine, which uses the same scanner).
        // Subclassing the target sidesteps the discovery entirely and is
        // explicit about *when* the auth is attached.
        context.setTarget(new AuthAwareHttpTarget(
                base.getHost(),
                port,
                base.getPath() == null || base.getPath().isEmpty() ? "/" : base.getPath()));
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void pactVerification(PactVerificationContext context) {
        context.verifyInteraction();
    }

    /**
     * Decorate the prepared HTTP request with an auth header and the
     * seeded claim id rewrite. Called by {@link AuthAwareHttpTarget}.
     */
    void attachAuth(HttpRequest request) {
        try {
            String token = mintToken();
            if (token != null) {
                request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            request.setHeader("X-User-Id", "pact-verifier-agent");

            // Swap the placeholder id 42 from the consumer pact for the
            // actually-seeded claim id, if a state handler stashed one.
            try {
                URI uri = request.getUri();
                if (uri != null && seededClaimId != null && uri.getPath().contains("/claims/42/")) {
                    String swapped = uri.getPath().replace("/claims/42/", "/claims/" + seededClaimId + "/");
                    URI rewritten = new URI(
                            uri.getScheme(), uri.getUserInfo(),
                            uri.getHost(), uri.getPort(),
                            swapped, uri.getQuery(), uri.getFragment());
                    request.setUri(rewritten);
                }
            } catch (Exception e) {
                System.err.println("[pact-verifier] uri rewrite failed: " + e.getMessage());
            }
        } catch (Exception e) {
            System.err.println("[pact-verifier] request filter failure: " + e.getMessage());
        }
    }

    // ---------- State handlers ----------

    @State("rate limit window is open for this vehicleVin")
    public void openRateLimitWindow(Map<String, Object> params) {
        // QuoteResource rate-limit keys are "ratelimit:quote:<vin>" with a
        // 60s sliding window. We evict the key for the consumer pact's
        // fixed VIN. If Redis isn't reachable the worst case is the
        // verification hits a 429 — that's a real contract failure to
        // surface, not something to silently paper over.
        try (RedisDel r = new RedisDel()) {
            r.del("ratelimit:quote:1HGBH41JXMN109186");
        } catch (Exception e) {
            System.err.println("[pact-state] Redis evict skipped: " + e.getMessage());
        }
    }

    @State("a claim with id 42 exists in FILED status against policy POL-100")
    public void seedFiledClaim(Map<String, Object> params) {
        String jdbcUrl  = System.getProperty("pact.db.url",  "jdbc:postgresql://localhost:5432/insurance");
        String jdbcUser = System.getProperty("pact.db.user", "insurance");
        String jdbcPass = System.getProperty("pact.db.pass", "insurance");
        // Per-run unique policy + a single re-used one for stability.
        // We create a new quote+policy pair every run because the policy
        // table's quote_id has a UNIQUE constraint; doing so keeps reruns
        // idempotent without needing to clean up.
        String policyNumber = "PACT-" + System.currentTimeMillis() % 1_000_000;
        try (Connection c = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPass)) {
            ensurePolicy(c, policyNumber);
            seededClaimId = insertFiledClaim(c, policyNumber);
            System.out.println("[pact-state] seeded claim id=" + seededClaimId + " policy=" + policyNumber + " status=FILED");
        } catch (Exception e) {
            System.err.println("[pact-state] DB seed skipped: " + e.getMessage());
        }
    }

    // ---------- Helpers ----------

    /**
     * Mint a fresh JWT by calling the provider's own {@code GET /api/auth/token}
     * (DevTokenResource). Returns null on any failure; the verification then
     * runs without an Authorization header and the @RolesAllowed-guarded
     * interactions will (correctly) fail with 401.
     */
    private String mintToken() {
        try {
            var req = java.net.http.HttpRequest.newBuilder(URI.create(PROVIDER_URL + "/api/auth/token"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) return null;
            // Tiny ad-hoc JSON extraction — keeps this test free of a JSON-B /
            // Jackson dep that the rest of test scope doesn't need.
            Matcher m = Pattern.compile("\"jwt\"\\s*:\\s*\"([^\"]+)\"").matcher(resp.body());
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void ensurePolicy(Connection c, String number) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT policy_number FROM policy WHERE policy_number = ?")) {
            ps.setString(1, number);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return;
            }
        }
        // The policy table requires a referenced quote_id. Seed a throwaway
        // quote row first so the FK constraint is satisfied. Both inserts
        // ON CONFLICT DO NOTHING so a re-run of the verifier doesn't blow
        // up if a previous run left rows behind.
        long quoteId;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO quote (vehicle_vin, driver_age, coverage_type, premium, status, created_at, valid_until) " +
                "VALUES ('1HGBH41JXMN109186', 35, 'STANDARD', 500.00, 'CALCULATED', now(), now() + interval '30 days') " +
                "RETURNING id")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("quote insert returned no id");
                quoteId = rs.getLong(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO policy (policy_number, quote_id, status, bound_at) " +
                "VALUES (?, ?, 'BOUND', now()) ON CONFLICT DO NOTHING")) {
            ps.setString(1, number);
            ps.setLong(2, quoteId);
            ps.executeUpdate();
        }
    }

    private static Long insertFiledClaim(Connection c, String policyNumber) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO claim (policy_number, description, status, filed_at) " +
                "VALUES (?, 'pact-verifier seed', 'FILED', now()) RETURNING id")) {
            ps.setString(1, policyNumber);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        throw new IllegalStateException("INSERT INTO claim returned no id");
    }

    /**
     * Extends Pact-JVM's HttpTestTarget so we can intercept the
     * already-prepared HttpUriRequest and add auth headers + path rewrites
     * before the verifier sends it. This is the same Object as what would
     * be passed to a @TargetRequestFilter — we just intercept it ourselves.
     */
    private final class AuthAwareHttpTarget extends HttpTestTarget {
        AuthAwareHttpTarget(String host, int port, String path) {
            super(host, port, path);
        }
        @Override
        public Pair<Object, Object> prepareRequest(Pact pact, Interaction interaction, Map<String, Object> context) {
            Pair<Object, Object> result = super.prepareRequest(pact, interaction, context);
            Object req = result.getFirst();
            if (req instanceof HttpRequest http) {
                attachAuth(http);
            }
            return result;
        }
    }

    /**
     * Tiny zero-dependency Redis client over the wire-level protocol so this
     * test class doesn't drag Lettuce into the verifier classpath. Implements
     * exactly enough to send a single DEL command and discard the reply.
     */
    private static final class RedisDel implements AutoCloseable {
        private final java.net.Socket sock;
        RedisDel() throws Exception {
            String host = System.getProperty("pact.redis.host", "localhost");
            int    port = Integer.parseInt(System.getProperty("pact.redis.port", "6379"));
            sock = new java.net.Socket();
            sock.connect(new java.net.InetSocketAddress(host, port), 1500);
            sock.setSoTimeout(1500);
        }
        void del(String key) throws Exception {
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            var out = sock.getOutputStream();
            String header = "*2\r\n$3\r\nDEL\r\n$" + keyBytes.length + "\r\n";
            out.write(header.getBytes(StandardCharsets.UTF_8));
            out.write(keyBytes);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            sock.getInputStream().read(new byte[64]); // drain reply
        }
        @Override public void close() throws Exception { sock.close(); }
    }
}
