package com.example.insurance.notification;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import jakarta.enterprise.concurrent.ManagedExecutorService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Multi-topic fan-in consumer: a single Kafka consumer subscribed to
 * {@code quote-events}, {@code policy-events}, and {@code payment-events}.
 * For each record, builds a {@link NotificationRequest} keyed off the
 * source topic and hands it to {@link NotificationService}.
 *
 * Why a raw {@link KafkaConsumer} + dedicated thread instead of
 * MP Reactive Messaging's {@code @Incoming}: per build_gotchas item 12,
 * Liberty 24.0.0.12's mpReactiveMessaging-3.0 + liberty-kafka silently
 * fails to subscribe an {@code @Incoming} consumer. The producer side
 * works fine (slice 3), the consumer side doesn't. Until that mystery is
 * solved, every consumer in this codebase uses the raw client — explicit,
 * boring, and verifiable via {@code kafka-consumer-groups.sh}.
 */
@ApplicationScoped
public class NotificationConsumer {

    private static final Logger LOG = Logger.getLogger(NotificationConsumer.class.getName());

    // Topics this consumer fans in from. Order matters only for log-readability.
    private static final List<String> TOPICS = List.of(
            "quote-events", "policy-events", "payment-events");

    @Inject
    NotificationService service;

    @Resource
    ManagedExecutorService executor;

    private KafkaConsumer<String, String> consumer;
    private Future<?> pollTask;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Jsonb jsonb = JsonbBuilder.create();

    /** Forces eager init of this @ApplicationScoped bean on app start —
        the consumer otherwise sits dormant until something injects it. */
    void start(@Observes @Initialized(ApplicationScoped.class) Object init) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "insurance-notification");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "insurance-app-notification-consumer");

        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(TOPICS);
        running.set(true);
        pollTask = executor.submit(this::pollLoop);
        LOG.info(() -> "NotificationConsumer started, subscribed to " + TOPICS);
    }

    @PreDestroy
    void stop() {
        running.set(false);
        if (consumer != null) consumer.wakeup();
        if (pollTask != null) pollTask.cancel(true);
    }

    private void pollLoop() {
        try {
            while (running.get()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : records) {
                    try {
                        handle(r);
                    } catch (Exception e) {
                        // One bad record can't kill the consumer — log and continue.
                        LOG.log(Level.WARNING, "Skipping bad record on " + r.topic() + ": " + e.getMessage(), e);
                    }
                }
                if (!records.isEmpty()) consumer.commitSync();
            }
        } catch (org.apache.kafka.common.errors.WakeupException w) {
            // expected on shutdown
        } finally {
            try { consumer.close(); } catch (Exception ignored) {}
            LOG.info("NotificationConsumer stopped");
        }
    }

    /**
     * Map a Kafka record to a {@link NotificationRequest}. Each source
     * topic produces a differently-templated email — channel is always
     * "email" for slice 8; sms/push lanes are wired in MI and WireMock
     * for future events that need them.
     */
    void handle(ConsumerRecord<String, String> r) {
        Map<String, Object> payload = jsonb.fromJson(r.value(), Map.class);
        NotificationRequest req = switch (r.topic()) {
            case "quote-events"   -> quoteNotification(payload);
            case "policy-events"  -> policyNotification(payload);
            case "payment-events" -> paymentNotification(payload);
            default -> null;
        };
        if (req == null) return;
        service.notify(r.topic(), r.key(), req);
    }

    private NotificationRequest quoteNotification(Map<String, Object> p) {
        String vin     = String.valueOf(p.getOrDefault("vehicleVin", "?"));
        Object premium = p.getOrDefault("premium", "?");
        return new NotificationRequest(
                "email",
                "customer@example.com",
                "Your quote is ready",
                "We've prepared a quote for VIN " + vin + ". Premium: $" + premium + ".");
    }

    private NotificationRequest policyNotification(Map<String, Object> p) {
        String pol = String.valueOf(p.getOrDefault("policyNumber", "?"));
        return new NotificationRequest(
                "email",
                "customer@example.com",
                "Welcome — your policy is active",
                "Policy " + pol + " is now bound. Thanks for choosing us.");
    }

    private NotificationRequest paymentNotification(Map<String, Object> p) {
        String pol    = String.valueOf(p.getOrDefault("policyNumber", "?"));
        Object amount = p.getOrDefault("amount", "?");
        String status = String.valueOf(p.getOrDefault("status", "?"));
        String subject = "SUCCEEDED".equals(status)
                ? "Payment received"
                : "Payment could not be processed";
        return new NotificationRequest(
                "email",
                "customer@example.com",
                subject,
                "Payment of $" + amount + " for policy " + pol + " is " + status + ".");
    }
}
