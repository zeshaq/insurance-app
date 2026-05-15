package com.example.insurance.report;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyWindowStore;
import org.apache.kafka.streams.state.WindowStoreIterator;
import org.apache.kafka.streams.StoreQueryParameters;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Kafka Streams topology for the agent reporting view.
 *
 *   source(payment-events)
 *     → groupBy(status from JSON body)
 *     → windowedBy(1 min tumbling, no grace)
 *     → count() into materialized store "payment-counts"
 *
 * Exposed as a {@link ReadOnlyWindowStore} keyed by status — REST queries
 * iterate windows from the last hour and roll them up.
 *
 * Lifecycle notes:
 *   - Started eagerly via {@code @Observes @Initialized(ApplicationScoped.class)} —
 *     same pattern as NotificationConsumer (slice 8) and
 *     AgentDashboardSubscriber (slice 11). @ApplicationScoped is otherwise
 *     lazy-init and nothing injects this bean directly.
 *   - The state store needs the topology to reach RUNNING before queries
 *     work. {@link #countByStatusLastHour()} treats not-yet-running as an
 *     empty result rather than throwing — the REST layer is then free to
 *     poll without spamming exceptions during a slow startup.
 */
@ApplicationScoped
public class PaymentReportStreams {

    private static final Logger LOG = Logger.getLogger(PaymentReportStreams.class.getName());

    public static final String STORE_NAME = "payment-counts";
    private static final String STATUS_KEY_PREFIX = "status:";

    private KafkaStreams streams;
    private final Jsonb jsonb = JsonbBuilder.create();

    void start(@Observes @Initialized(ApplicationScoped.class) Object init) {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG,    "insurance-app-payment-report");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:9092");
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG,   Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        // Earliest so a freshly-started topology backfills from the topic head.
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        StreamsBuilder b = new StreamsBuilder();
        b.<String, String>stream("payment-events")
                .filter((k, v) -> v != null && !v.isBlank())
                .groupBy((k, v) -> STATUS_KEY_PREFIX + extractStatus(v),
                         org.apache.kafka.streams.kstream.Grouped.with(Serdes.String(), Serdes.String()))
                .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(1)))
                .count(Materialized.as(STORE_NAME));

        streams = new KafkaStreams(b.build(), props);
        streams.setUncaughtExceptionHandler(ex -> {
            LOG.log(Level.SEVERE, "Streams thread died", ex);
            return org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT;
        });
        streams.start();
        LOG.info("PaymentReportStreams started, store=" + STORE_NAME);
    }

    @PreDestroy
    void stop() {
        if (streams != null) streams.close(Duration.ofSeconds(5));
    }

    String extractStatus(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = jsonb.fromJson(json, Map.class);
            Object s = m.get("status");
            return s == null ? "UNKNOWN" : s.toString();
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    /** Roll up window counts for the last hour grouped by status. */
    public List<WindowedCount> countByStatusLastHour() {
        List<WindowedCount> out = new ArrayList<>();
        if (streams == null || streams.state() != KafkaStreams.State.RUNNING) return out;
        try {
            ReadOnlyWindowStore<String, Long> store = streams.store(
                    StoreQueryParameters.fromNameAndType(STORE_NAME, QueryableStoreTypes.windowStore()));
            Instant to   = Instant.now();
            Instant from = to.minus(Duration.ofHours(1));
            for (String status : new String[] {"SUCCEEDED", "FAILED", "UNKNOWN"}) {
                try (WindowStoreIterator<Long> it = store.fetch(STATUS_KEY_PREFIX + status, from, to)) {
                    while (it.hasNext()) {
                        var kv = it.next();
                        out.add(new WindowedCount(status, kv.key, kv.key + Duration.ofMinutes(1).toMillis(), kv.value));
                    }
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Streams state-store query failed", e);
        }
        return out;
    }

    /** Totals collapsed across windows — used by the snapshot endpoint. */
    public Map<String, Long> totalsByStatusLastHour() {
        java.util.Map<String, Long> totals = new java.util.LinkedHashMap<>();
        for (WindowedCount w : countByStatusLastHour()) {
            totals.merge(w.status(), w.count(), Long::sum);
        }
        return totals;
    }

    public record WindowedCount(String status, long windowStart, long windowEnd, long count) {}
}
