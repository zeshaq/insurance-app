package com.example.insurance.search;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Path("/search")
@Produces(MediaType.APPLICATION_JSON)
public class SearchResource {

    @Inject OpenSearchClient os;

    /**
     * Multi-field full-text search over claim documents. Fields with text
     * payloads — description, ocr_text — get analyzed for relevance; ID-like
     * fields (other_party_vin, policy_number, status) are matched as-is.
     *
     * Force-refresh is intentional: the OpenSearch default 1s refresh
     * interval is fine for production but it makes the smoke flaky.
     * Forcing a refresh costs ~5ms and removes the timing dependency.
     */
    @GET
    @Path("/claims")
    @SuppressWarnings("unchecked")
    public SearchResponse search(@QueryParam("q") String q,
                                 @QueryParam("size") Integer size) throws Exception {
        if (q == null || q.isBlank()) {
            return new SearchResponse(0, List.of());
        }
        os.refresh("claims");
        Map<String, Object> body = Map.of(
                "size", size == null ? 20 : Math.max(1, Math.min(100, size)),
                "query", Map.of(
                        "multi_match", Map.of(
                                "query",  q,
                                "fields", List.of("description^2", "ocr_text", "other_party_vin",
                                                  "other_party_carrier", "policy_number", "status"))));
        Map<String, Object> raw = os.search("claims", body);
        Map<String, Object> hitsOuter = (Map<String, Object>) raw.getOrDefault("hits", Map.of());
        Map<String, Object> total     = (Map<String, Object>) hitsOuter.getOrDefault("total", Map.of("value", 0));
        long totalValue;
        Object tv = total.get("value");
        if      (tv instanceof Number n) totalValue = n.longValue();
        else if (tv != null)             totalValue = Long.parseLong(tv.toString());
        else                              totalValue = 0L;

        List<Map<String, Object>> hits = (List<Map<String, Object>>) hitsOuter.getOrDefault("hits", List.of());
        List<Hit> out = new ArrayList<>();
        for (Map<String, Object> h : hits) {
            Map<String, Object> src = (Map<String, Object>) h.getOrDefault("_source", Map.of());
            Object score = h.get("_score");
            out.add(new Hit(
                    h.get("_id") == null ? null : h.get("_id").toString(),
                    src,
                    score instanceof Number n ? n.doubleValue() : 0.0));
        }
        return new SearchResponse(totalValue, out);
    }

    public record SearchResponse(long total, List<Hit> hits) {}
    public record Hit(String id, Map<String, Object> source, double score) {}
}
