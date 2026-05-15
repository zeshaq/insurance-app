package com.example.insurance.quote;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;

@ApplicationScoped
public class QuoteService {

    private static final BigDecimal BASE_RATE = new BigDecimal("500.00");
    private static final Duration   VALIDITY  = Duration.ofDays(30);

    @Inject
    QuoteRepository repo;

    @Transactional
    public Quote createQuote(QuoteRequest req) {
        BigDecimal coverageFactor = switch (req.coverageType()) {
            case "BASIC"    -> new BigDecimal("1.0");
            case "STANDARD" -> new BigDecimal("1.5");
            case "PREMIUM"  -> new BigDecimal("2.0");
            default         -> throw new IllegalArgumentException(
                    "Unknown coverageType: " + req.coverageType() + " (expected BASIC | STANDARD | PREMIUM)");
        };
        BigDecimal ageFactor = (req.driverAge() < 25 || req.driverAge() > 70)
                ? new BigDecimal("1.4")
                : BigDecimal.ONE;

        BigDecimal premium = BASE_RATE
                .multiply(coverageFactor)
                .multiply(ageFactor)
                .setScale(2, RoundingMode.HALF_UP);

        Quote q = new Quote();
        q.setVehicleVin(req.vehicleVin());
        q.setDriverAge(req.driverAge());
        q.setCoverageType(req.coverageType());
        q.setPremium(premium);
        q.setStatus("CALCULATED");
        OffsetDateTime now = OffsetDateTime.now();
        q.setCreatedAt(now);
        q.setValidUntil(now.plus(VALIDITY));

        return repo.save(q);
    }
}
