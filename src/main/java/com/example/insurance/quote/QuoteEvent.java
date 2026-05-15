package com.example.insurance.quote;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The wire-level event we publish to the `quote-events` Kafka topic when a
 * quote is created. Plain JSON for now (slice 3); Avro + Schema Registry
 * arrive in the Kafka deep-dive module.
 */
public record QuoteEvent(
        String         eventType,    // "quote.calculated"
        Long           quoteId,
        String         vehicleVin,
        Integer        driverAge,
        String         coverageType,
        BigDecimal     premium,
        OffsetDateTime timestamp) {

    public static QuoteEvent calculated(Quote q) {
        return new QuoteEvent(
                "quote.calculated",
                q.getId(),
                q.getVehicleVin(),
                q.getDriverAge(),
                q.getCoverageType(),
                q.getPremium(),
                OffsetDateTime.now());
    }
}
