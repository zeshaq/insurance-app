package com.example.insurance.search;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tiny HTTP shim over OpenSearch's REST API. mpRestClient would also work,
 * but OpenSearch's responses are deeply nested (hits.hits[].\_source...) and
 * passing typed DTOs through mpRestClient adds Jackson-binding overhead
 * that isn't worth it for this slice. Jdk HttpClient + Jsonb is direct,
 * type-erased Map, and easy to debug.
 *
 * Security: OpenSearch in this compose runs with security disabled (set via
 * env var in the compose service definition). No auth header needed; would
 * become an mTLS or basic-auth concern in production.
 */
@ApplicationScoped
public class OpenSearchClient {

    private static final Logger LOG = Logger.getLogger(OpenSearchClient.class.getName());
    private static final String BASE = "http://opensearch:9200";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final Jsonb jsonb = JsonbBuilder.create();

    @PreDestroy
    void close() { /* HttpClient has no close() in JDK 21 */ }

    /** PUT /{index}/_doc/{id} — upsert. */
    public void put(String index, String id, Map<String, Object> doc) throws Exception {
        String body = jsonb.toJson(doc);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/" + index + "/_doc/" + id))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            LOG.log(Level.WARNING, () -> "OpenSearch PUT " + index + "/" + id + " -> " + resp.statusCode() + " " + resp.body());
        }
    }

    public void delete(String index, String id) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/" + index + "/_doc/" + id))
                .timeout(Duration.ofSeconds(5))
                .DELETE()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        // 404 on delete is fine — already gone.
    }

    /** POST /{index}/_search with the given query body. Returns parsed response. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> search(String index, Map<String, Object> queryBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/" + index + "/_search"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(jsonb.toJson(queryBody)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) {
            return Map.of("hits", Map.of("total", Map.of("value", 0), "hits", java.util.List.of()));
        }
        return jsonb.fromJson(resp.body(), Map.class);
    }

    /** GET /{index}/_refresh — force refresh so just-indexed docs are searchable. */
    public void refresh(String index) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/" + index + "/_refresh"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        http.send(req, HttpResponse.BodyHandlers.discarding());
    }
}
