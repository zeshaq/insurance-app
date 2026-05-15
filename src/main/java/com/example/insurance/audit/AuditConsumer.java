package com.example.insurance.audit;

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
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Rebuilds {@link AuditSnapshot} from the {@code audit-events} compacted
 * topic on every boot.
 *
 * Key design choices:
 *   - {@code group.id} is fresh per process (UUID) so the consumer always
 *     reads from {@code earliest}, regardless of any committed offsets that
 *     happened to survive a previous run. The whole point is "rehydrate
 *     state from the topic" — that has to start at zero.
 *   - Runs on {@link ManagedExecutorService} (gotcha 15: raw threads lose
 *     Liberty's UOWCoordinator, even though THIS consumer doesn't use
 *     @Transactional — staying consistent with the other consumers).
 */
@ApplicationScoped
public class AuditConsumer {

    private static final Logger LOG = Logger.getLogger(AuditConsumer.class.getName());

    @Inject AuditSnapshot snapshot;

    @Resource
    ManagedExecutorService executor;

    private KafkaConsumer<String, String> consumer;
    private Future<?> task;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Jsonb jsonb = JsonbBuilder.create();

    void start(@Observes @Initialized(ApplicationScoped.class) Object init) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG,          "insurance-audit-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "insurance-app-audit-consumer");

        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(java.util.List.of(AuditPublisher.TOPIC));
        running.set(true);
        task = executor.submit(this::pollLoop);
        LOG.info("AuditConsumer started, rebuilding snapshot from " + AuditPublisher.TOPIC);
    }

    @PreDestroy
    void stop() {
        running.set(false);
        if (consumer != null) consumer.wakeup();
        if (task != null) task.cancel(true);
    }

    private void pollLoop() {
        try {
            while (running.get()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : records) {
                    try {
                        AuditEvent e = jsonb.fromJson(r.value(), AuditEvent.class);
                        snapshot.put(e);
                    } catch (Exception ex) {
                        LOG.log(Level.FINE, "Skipping malformed audit record: " + ex.getMessage());
                    }
                }
            }
        } catch (org.apache.kafka.common.errors.WakeupException w) {
            // shutdown
        } finally {
            try { consumer.close(); } catch (Exception ignored) {}
            LOG.info("AuditConsumer stopped");
        }
    }
}
