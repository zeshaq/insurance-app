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
 * Dead-letter publisher for payments whose gateway call exhausted retries.
 * Keyed by idempotency-key so a downstream operator-replay tool can pick
 * up exactly one DLQ record per failed charge attempt.
 *
 * Why a separate topic instead of a status field on payment-events: the DLQ
 * pattern's value is operational isolation — failed payments are routed to
 * a topic operators specifically subscribe to, with retention/alerting
 * policies that may differ from the success path. Mixing both on one topic
 * forces every consumer to filter and risks "missed alert because someone
 * forgot to add WHERE status=FAILED" — a class of bug the DLQ pattern is
 * specifically designed to prevent.
 */
@ApplicationScoped
public class PaymentDlqPublisher {

    private static final Logger LOG = Logger.getLogger(PaymentDlqPublisher.class.getName());

    private KafkaProducer<String, String> producer;
    private final Jsonb jsonb = JsonbBuilder.create();

    @PostConstruct
    void init() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "insurance-app-payment-dlq");
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        producer = new KafkaProducer<>(props);
        LOG.info("payment-dlq KafkaProducer initialized");
    }

    @PreDestroy
    void close() {
        if (producer != null) producer.close();
    }

    public void publish(Payment p, String reason, int attempts) {
        String payload = jsonb.toJson(new DlqRecord(
                p.getIdempotencyKey(), p.getPolicyNumber(),
                p.getAmount().toPlainString(), p.getCurrency(),
                reason, attempts));
        try {
            producer.send(new ProducerRecord<>("payment-dlq", p.getIdempotencyKey(), payload)).get();
            LOG.warning(() -> "Dead-lettered payment for key=" + p.getIdempotencyKey() + " reason=" + reason);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to publish DLQ record for " + p.getIdempotencyKey(), e);
        }
    }

    public record DlqRecord(String idempotencyKey, String policyNumber, String amount,
                            String currency, String reason, int attempts) {}
}
