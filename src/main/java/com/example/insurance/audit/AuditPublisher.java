package com.example.insurance.audit;

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
 * Writes {@link AuditEvent}s to the compacted {@code audit-events} topic.
 * Same {@link AuditEvent#key()} on multiple writes is the whole point — log
 * compaction keeps the latest one, so the topic acts as a current-state
 * snapshot. ACKS=all keeps the broker honest on durability.
 */
@ApplicationScoped
public class AuditPublisher {

    private static final Logger LOG = Logger.getLogger(AuditPublisher.class.getName());
    public  static final String TOPIC = "audit-events";

    private KafkaProducer<String, String> producer;
    private final Jsonb jsonb = JsonbBuilder.create();

    @PostConstruct
    void init() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "insurance-app-audit");
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        producer = new KafkaProducer<>(props);
        LOG.info("audit-events KafkaProducer initialized");
    }

    @PreDestroy
    void close() {
        if (producer != null) producer.close();
    }

    public void publish(AuditEvent event) {
        try {
            String payload = jsonb.toJson(event);
            producer.send(new ProducerRecord<>(TOPIC, event.key(), payload)).get();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "audit publish failed for " + event.key(), e);
        }
    }
}
