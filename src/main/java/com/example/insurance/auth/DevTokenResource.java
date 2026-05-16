package com.example.insurance.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Server-side JWT mint for the browser GUI (slice 15).
 *
 * The GUI is a teaching artifact — no users, no login screen. On page load
 * the browser calls GET /api/auth/token, server exchanges its client_credentials
 * with WSO2 IS over the internal HTTP port (9763 — same host that mpJwt uses
 * for JWKS, no self-signed-cert handshake to worry about), and returns the
 * JWT to the browser. JS stashes it in memory and sends it on every fetch().
 *
 * Cached in a volatile field for 55 minutes (real expiry is 60). One token
 * per process, shared across browser tabs. For a single-VM cohort demo this
 * is fine; a real multi-user GUI would do an OIDC code flow per user.
 *
 * Credentials come from WSO2IS_CLIENT_ID / WSO2IS_CLIENT_SECRET env vars
 * passed to the container at run time (see scripts/build.sh advertised
 * podman run command).
 */
@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class DevTokenResource {

    private static final Logger LOG = Logger.getLogger(DevTokenResource.class.getName());
    private static final String TOKEN_URL = "http://wso2is:9763/oauth2/token";
    private static final Duration REFRESH_BEFORE_EXPIRY = Duration.ofMinutes(5);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private final Jsonb jsonb = JsonbBuilder.create();

    private volatile String   cachedJwt;
    private volatile Instant  cachedExpiry = Instant.MIN;

    @GET
    @Path("/token")
    public Response token() {
        String clientId     = System.getenv("WSO2IS_CLIENT_ID");
        String clientSecret = System.getenv("WSO2IS_CLIENT_SECRET");
        if (clientId == null || clientSecret == null) {
            return Response.serverError()
                    .entity(Map.of("error", "WSO2IS_CLIENT_ID / WSO2IS_CLIENT_SECRET env vars not set on Liberty"))
                    .build();
        }
        try {
            String jwt = ensureFresh(clientId, clientSecret);
            return Response.ok(Map.of("jwt", jwt,
                    "expiresIn", Math.max(0, cachedExpiry.getEpochSecond() - Instant.now().getEpochSecond())))
                    .build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Token mint failed", e);
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity(Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()))
                    .build();
        }
    }

    @SuppressWarnings("unchecked")
    private synchronized String ensureFresh(String clientId, String clientSecret) throws Exception {
        if (cachedJwt != null && Instant.now().isBefore(cachedExpiry.minus(REFRESH_BEFORE_EXPIRY))) {
            return cachedJwt;
        }
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        String body  = "grant_type=client_credentials";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("WSO2 IS returned " + resp.statusCode() + ": " + resp.body());
        }
        Map<String, Object> json = jsonb.fromJson(resp.body(), Map.class);
        cachedJwt = String.valueOf(json.get("access_token"));
        long expiresIn = json.get("expires_in") instanceof Number n ? n.longValue() : 3600L;
        cachedExpiry  = Instant.now().plusSeconds(expiresIn);
        LOG.info(() -> "Minted fresh dev JWT (expires in " + expiresIn + "s)");
        return cachedJwt;
    }
}
