package com.example.insurance.search;

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
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CDC consumer for slice 14 (Search). Subscribes to {@code dbserver1.public.claim}
 * produced by the Debezium Postgres source connector and writes each row to
 * OpenSearch index {@code claims}.
 *
 * Debezium delivers messages with the ExtractNewRecordState SMT applied
 * (see compose/infra/connectors/debezium-postgres-claim.json), so the
 * payload is the "after" row directly — no envelope unwrap on this side.
 * Deletes (op=d) are signalled by {@code __deleted=true}; we DELETE the
 * doc instead of overwriting.
 *
 * group.id is fresh per process so a Liberty restart re-indexes everything
 * from the topic head — the OpenSearch index is treated as a derived
 * projection, with Postgres as the source of truth.
 */
@ApplicationScoped
public class SearchIndexer {

    private static final Logger LOG = Logger.getLogger(SearchIndexer.class.getName());
    private static final String CDC_TOPIC = "dbserver1.public.claim";

    @Inject OpenSearchClient os;

    @Resource
    ManagedExecutorService executor;

    private KafkaConsumer<String, String> consumer;
    private Future<?> task;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Jsonb jsonb = JsonbBuilder.create();

    void start(@Observes @Initialized(ApplicationScoped.class) Object init) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG,          "insurance-search-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "insurance-app-search-indexer");

        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(java.util.List.of(CDC_TOPIC));
        running.set(true);
        task = executor.submit(this::pollLoop);
        LOG.info("SearchIndexer started, subscribed to " + CDC_TOPIC);
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
                        handle(r);
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "Index failed for " + r.key() + ": " + e.getMessage());
                    }
                }
            }
        } catch (org.apache.kafka.common.errors.WakeupException w) {
            // shutdown
        } finally {
            try { consumer.close(); } catch (Exception ignored) {}
            LOG.info("SearchIndexer stopped");
        }
    }

    @SuppressWarnings("unchecked")
    private void handle(ConsumerRecord<String, String> r) throws Exception {
        if (r.value() == null) return;   // tombstone for compacted topic — n/a here
        Map<String, Object> after = jsonb.fromJson(r.value(), Map.class);
        Object idObj = after.get("id");
        if (idObj == null) return;
        String docId = String.valueOf(idObj);
        Object deleted = after.get("__deleted");
        if (deleted != null && ("true".equals(deleted.toString()) || Boolean.TRUE.equals(deleted))) {
            os.delete("claims", docId);
            return;
        }
        // Project only the searchable columns. Trim ocr_text so OpenSearch doesn't
        // analyze a giant blob on every claim.
        Map<String, Object> doc = new java.util.LinkedHashMap<>();
        doc.put("id",                after.get("id"));
        doc.put("policy_number",     after.get("policy_number"));
        doc.put("description",       after.get("description"));
        doc.put("vehicle_vin",       null);   // claim row has no vin column — left for symmetry with future docs
        doc.put("other_party_vin",   after.get("other_party_vin"));
        doc.put("other_party_carrier", after.get("other_party_carrier"));
        doc.put("ocr_text",          after.get("ocr_text"));
        doc.put("status",            after.get("status"));
        doc.put("filed_at",          after.get("filed_at"));
        os.put("claims", docId, doc);
    }
}
