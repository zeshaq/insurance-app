package com.example.insurance.claim;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes every claim state change to the retention-based {@code claim-events}
 * topic. Foil to {@link com.example.insurance.audit.AuditPublisher} — both
 * receive the same logical event, but claim-events is configured with
 * retention.ms=30d (no compaction) so every transition stays in the log
 * for a month.
 *
 * Retention vs compaction is the slice 13 pedagogy: the same domain event
 * lands on TWO topics with different cleanup policies, and the contrast
 * endpoint (/api/audit/contrast/{claimId}) shows the resulting shape of
 * each. Engineers learn to pick by use case — running history vs
 * current-state projection — not by reflex.
 */
@ApplicationScoped
public class ClaimEventPublisher {

    private static final Logger LOG = Logger.getLogger(ClaimEventPublisher.class.getName());
    public  static final String TOPIC = "claim-events";

    private KafkaProducer<String, String> producer;
    private final Jsonb jsonb = JsonbBuilder.create();

    @PostConstruct
    void init() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "insurance-app-claim-events");
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        producer = new KafkaProducer<>(props);
        LOG.info("claim-events KafkaProducer initialized");
    }

    @PreDestroy
    void close() {
        if (producer != null) producer.close();
    }

    public void publish(Long claimId, String action, Claim claim) {
        try {
            String payload = jsonb.toJson(new Event(claimId, action,
                    claim.getPolicyNumber(),
                    claim.getStatus(),
                    claim.getOcrConfidence() == null ? null : claim.getOcrConfidence().toPlainString(),
                    claim.getOtherPartyCarrier(),
                    claim.getFiledAt() == null ? null : claim.getFiledAt().toString()));
            producer.send(new ProducerRecord<>(TOPIC, String.valueOf(claimId), payload)).get();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "claim-events publish failed for " + claimId, e);
        }
    }

    public record Event(Long claimId, String action, String policyNumber,
                        String status, String ocrConfidence,
                        String otherPartyCarrier, String filedAt) {}
}
