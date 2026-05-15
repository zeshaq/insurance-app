package com.example.insurance.quote;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.logging.Logger;

@ApplicationScoped
public class QuoteService {

    private static final Logger     LOG       = Logger.getLogger(QuoteService.class.getName());
    private static final BigDecimal BASE_RATE = new BigDecimal("500.00");
    private static final Duration   VALIDITY  = Duration.ofDays(30);

    @Inject
    QuoteRepository repo;

    @Inject
    QuoteCache cache;

    @Inject
    QuotePublisher publisher;

    @Inject
    @RestClient
    CreditBureauClient creditBureau;

    @Inject
    CreditScoreCache creditCache;

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
        BigDecimal creditFactor = lookupCreditFactor(req.vehicleVin());

        BigDecimal premium = BASE_RATE
                .multiply(coverageFactor)
                .multiply(ageFactor)
                .multiply(creditFactor)
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

        Quote saved = repo.save(q);
        cache.put(saved);
        publisher.publishCalculated(saved);
        return saved;
    }

    /**
     * Read-through cache: hit Redis first; on miss, query the DB and populate
     * the cache. Per ADR 0005 the TTL is 15 min and the key is {@code quote:{id}}.
     */
    public Quote getById(Long id) {
        Quote cached = cache.get(id);
        if (cached != null) {
            return cached;
        }
        Quote fresh = repo.findById(id);
        if (fresh != null) {
            cache.put(fresh);
        }
        return fresh;
    }

    /**
     * Score → premium factor. Caches the bureau result in Redis for 1 hour
     * (key: {@code credit:{vin}}); a flurry of quote refinements for the same
     * VIN pays for one bureau call. On any bureau-side failure we fall back to
     * a neutral 1.0 — a 5xx because the bureau is briefly down would be worse
     * UX than a quote that happens to use the default rate. A future iteration
     * would add `@Retry` + `@Fallback` from mpFaultTolerance.
     */
    private BigDecimal lookupCreditFactor(String vin) {
        CreditScore score = creditCache.get(vin);
        if (score == null) {
            try {
                score = creditBureau.lookup(vin);
                if (score != null) creditCache.put(vin, score);
            } catch (Exception e) {
                LOG.warning(() -> "Credit bureau lookup failed for " + vin + ": " + e.getMessage()
                        + " — using neutral factor 1.0.");
                return new BigDecimal("1.0");
            }
        }
        int s = score == null || score.score() == null ? 720 : score.score();
        if (s >= 700) return new BigDecimal("1.0");
        if (s >= 600) return new BigDecimal("1.2");
        return new BigDecimal("1.5");
    }
}
