package com.example.insurance.quote;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import java.util.logging.Logger;

/**
 * Emits {@link QuoteEvent}s onto the `quote-events` channel. The channel is
 * bound to the Kafka topic of the same name in
 * `src/main/resources/META-INF/microprofile-config.properties`.
 *
 * Slice 3 sends plain JSON strings — Kafka partitions them round-robin.
 * Slice 4 (or whenever a consumer cares about per-quote ordering) switches
 * to a keyed Record so all events for a given quoteId land on the same
 * partition.
 */
@ApplicationScoped
public class QuotePublisher {

    private static final Logger LOG = Logger.getLogger(QuotePublisher.class.getName());

    @Inject
    @Channel("quote-events")
    Emitter<String> emitter;

    private final Jsonb jsonb = JsonbBuilder.create();

    public void publishCalculated(Quote q) {
        QuoteEvent event = QuoteEvent.calculated(q);
        String payload = jsonb.toJson(event);
        emitter.send(payload);
        LOG.fine(() -> "Emitted quote.calculated for id=" + q.getId());
    }
}
