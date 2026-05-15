package com.example.insurance.policy;

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
 * Publishes policy state changes onto the {@code policy-events} topic.
 *
 * The topic is created with {@code cleanup.policy=compact} (see kafka-init in
 * compose.yaml). Compaction keeps only the latest record per *key* — so the
 * key here MUST be the policyNumber, not a random partition strategy. A late
 * consumer that joins next year will see every currently-bound policy exactly
 * once instead of replaying the whole bind history.
 *
 * Why a raw {@code KafkaProducer} instead of MicroProfile Reactive Messaging
 * (which the quote-events publisher uses): MP-RM's {@code Emitter.send(payload)}
 * is round-robin keyless. Sending a *keyed* record through MP-RM needs
 * SmallRye-specific metadata classes that aren't on the compile classpath in
 * Liberty 24.0.0.12. For a compacted topic the key is non-negotiable, so we
 * drop down to the Kafka client directly. ADR 0005 calls this out as the
 * expected pattern when keyed delivery matters.
 */
@ApplicationScoped
public class PolicyPublisher {

    private static final Logger LOG = Logger.getLogger(PolicyPublisher.class.getName());

    private KafkaProducer<String, String> producer;
    private final Jsonb jsonb = JsonbBuilder.create();

    @PostConstruct
    void init() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "insurance-app-policy");
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        producer = new KafkaProducer<>(props);
        LOG.info("policy-events KafkaProducer initialized");
    }

    @PreDestroy
    void close() {
        if (producer != null) producer.close();
    }

    public void publishStateChange(Policy p) {
        String payload = jsonb.toJson(new Event(
                p.getPolicyNumber(), p.getQuoteId(), p.getStatus(),
                p.getBoundAt().toString()));
        try {
            producer.send(new ProducerRecord<>("policy-events", p.getPolicyNumber(), payload)).get();
            LOG.fine(() -> "Emitted policy state change for " + p.getPolicyNumber());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to publish policy state change for " + p.getPolicyNumber(), e);
            throw new RuntimeException(e);
        }
    }

    public record Event(String policyNumber, Long quoteId, String status, String boundAt) {}
}
