package com.example.insurance.audit;

import com.example.insurance.claim.ClaimEventPublisher;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

@Path("/audit")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class AuditResource {

    @Inject AuditSnapshot snapshot;

    private final Jsonb jsonb = JsonbBuilder.create();

    /** Compacted-topic projection: one row per entity (the latest action). */
    @GET
    @Path("/{entityType}/{entityId}")
    public AuditEvent latest(@PathParam("entityType") String type,
                             @PathParam("entityId")   String id) {
        return snapshot.get(type, id);
    }

    /** All current audit-snapshot entries — diagnostic. */
    @GET
    @Path("/all")
    public Collection<AuditEvent> all() {
        return snapshot.all();
    }

    /**
     * Retention vs compaction contrast: returns the SAME claim's view from
     * both topics so a teacher can show the two shapes side by side.
     *   - snapshot: one AuditEvent (latest only — compacted)
     *   - events:   every claim-event published for this claim (retention)
     *
     * Reads claim-events ad-hoc with a one-shot consumer because a topic
     * with 30-day retention is too big for a permanent in-memory projection;
     * this endpoint scans only the partitions and filters by claimId.
     */
    @GET
    @Path("/contrast/{claimId}")
    public Map<String, Object> contrast(@PathParam("claimId") String claimId) {
        return Map.of(
                "claimId",  claimId,
                "snapshot", snapshot.get("claim", claimId),
                "events",   readClaimEvents(claimId));
    }

    /** Read the entire claim-events topic and filter records by key. */
    private List<Map<String, Object>> readClaimEvents(String claimId) {
        List<Map<String, Object>> out = new ArrayList<>();
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG,          "audit-contrast-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(ClaimEventPublisher.TOPIC));
            long deadline = System.currentTimeMillis() + 5_000;
            boolean sawAny = false;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> recs = consumer.poll(Duration.ofMillis(500));
                if (recs.isEmpty() && sawAny) break;
                for (ConsumerRecord<String, String> r : recs) {
                    sawAny = true;
                    if (claimId.equals(r.key())) {
                        out.add(jsonb.fromJson(r.value(), Map.class));
                    }
                }
            }
        } catch (Exception e) {
            return List.of(Map.of("error", e.getMessage()));
        }
        return out;
    }
}
