package com.example.insurance.payment;

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
 * Emits payment outcomes (SUCCEEDED + FAILED) onto the {@code payment-events}
 * topic, keyed by paymentId. Separate from {@link PaymentDlqPublisher} on
 * purpose: payment-events is the success/failure ledger (90-day retention
 * per kafka-init), payment-dlq is the operator-replay queue for charges
 * that need attention. A notification consumer needs the ledger to "send
 * a receipt"; a payment-recovery service needs the DLQ to retry.
 */
@ApplicationScoped
public class PaymentEventPublisher {

    private static final Logger LOG = Logger.getLogger(PaymentEventPublisher.class.getName());

    private KafkaProducer<String, String> producer;
    private final Jsonb jsonb = JsonbBuilder.create();

    @PostConstruct
    void init() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "insurance-app-payment-events");
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        producer = new KafkaProducer<>(props);
        LOG.info("payment-events KafkaProducer initialized");
    }

    @PreDestroy
    void close() {
        if (producer != null) producer.close();
    }

    public void publish(Payment p) {
        String payload = jsonb.toJson(new Event(
                p.getId(), p.getPolicyNumber(), p.getAmount().toPlainString(),
                p.getCurrency(), p.getStatus(), p.getExternalRef(),
                p.getProcessedAt() == null ? null : p.getProcessedAt().toString()));
        try {
            producer.send(new ProducerRecord<>("payment-events", String.valueOf(p.getId()), payload)).get();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to publish payment-events for payment " + p.getId(), e);
        }
    }

    public record Event(Long paymentId, String policyNumber, String amount,
                        String currency, String status, String externalRef, String processedAt) {}
}
