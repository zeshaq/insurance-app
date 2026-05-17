package com.example.insurance.policy;

import com.example.insurance.quote.Quote;
import com.example.insurance.quote.QuoteRepository;

import com.example.insurance.audit.AuditEvent;
import com.example.insurance.audit.AuditPublisher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.logging.Logger;

@ApplicationScoped
public class PolicyService {

    private static final Logger   LOG      = Logger.getLogger(PolicyService.class.getName());
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);

    @Inject PolicyRepository  repo;
    @Inject QuoteRepository   quoteRepo;
    @Inject Redlock           redlock;
    @Inject PolicyPublisher   publisher;
    @Inject AuditPublisher    audit;

    public record BindResult(Policy policy, boolean created) {}

    /**
     * Idempotent bind: same quoteId yields the same policy. Concurrent binds
     * for the same quote serialize on Redis (via {@link Redlock}); the DB's
     * UNIQUE(quote_id) constraint is the backstop if the lock is ever lost.
     *
     * Returns BindResult.created=true the first time, false on subsequent
     * calls — the resource layer turns that into 201 vs 200.
     */
    @Transactional
    public BindResult bind(Long quoteId) {
        Policy existing = repo.findByQuoteId(quoteId);
        if (existing != null) return new BindResult(existing, false);

        Quote quote = quoteRepo.findById(quoteId);
        if (quote == null) throw new NotFoundException("quote " + quoteId);

        String lockKey = "lock:policy:quote:" + quoteId;
        String token = redlock.tryAcquire(lockKey, LOCK_TTL);
        if (token == null) {
            // Another bind for this quote is in flight. The caller can retry.
            throw new WebApplicationException(
                    "concurrent bind in progress for quote " + quoteId,
                    Response.Status.CONFLICT);
        }
        try {
            // Re-check after the lock — the holder may have just committed.
            existing = repo.findByQuoteId(quoteId);
            if (existing != null) return new BindResult(existing, false);

            Policy p = new Policy();
            p.setPolicyNumber("POL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            p.setQuoteId(quoteId);
            p.setStatus("BOUND");
            p.setBoundAt(OffsetDateTime.now());
            Policy saved = repo.save(p);
            publisher.publishStateChange(saved);
            try {
                String state = String.format(
                        "{\"policyNumber\":\"%s\",\"quoteId\":%d,\"status\":\"%s\"}",
                        saved.getPolicyNumber(), saved.getQuoteId(), saved.getStatus());
                audit.publish(new AuditEvent("policy", saved.getPolicyNumber(), "BOUND",
                        "system", state, java.time.OffsetDateTime.now().toString()));
            } catch (Exception e) {
                LOG.log(java.util.logging.Level.WARNING,
                        "audit-events publish failed for policy " + saved.getPolicyNumber(), e);
            }
            LOG.info(() -> "Policy " + saved.getPolicyNumber() + " bound to quote " + quoteId);
            return new BindResult(saved, true);
        } finally {
            redlock.release(lockKey, token);
        }
    }

    public Policy getByNumber(String policyNumber) {
        return repo.findByNumber(policyNumber);
    }
}
