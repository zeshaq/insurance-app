package com.example.insurance.payment;

import com.example.insurance.policy.Policy;
import com.example.insurance.policy.PolicyRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.time.OffsetDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class PaymentService {

    private static final Logger LOG = Logger.getLogger(PaymentService.class.getName());

    @Inject PaymentRepository       repo;
    @Inject PolicyRepository        policyRepo;
    @Inject PaymentDlqPublisher     dlq;
    @Inject IdempotencyStore        idem;
    @Inject PaymentGatewayInvoker   gatewayInvoker;

    /**
     * Process a payment with idempotent semantics.
     *
     * Layers (top wins, bottom is backstop):
     *   1. Redis cache lookup by idempotency-key — fast replay path.
     *   2. DB lookup by idempotency-key (UNIQUE) — survives Redis evictions.
     *   3. Fresh charge: save PENDING, call gateway through @Retry, then
     *      finalize SUCCEEDED or FAILED in its OWN transaction so a failure
     *      still produces a durable record (the original slice 7 bug threw
     *      WebApplicationException inside the outer @Transactional and
     *      JTA rolled the FAILED row right back — no DB evidence the
     *      payment was ever attempted).
     */
    public Result process(String idempotencyKey, PaymentRequest req) {
        Payment replayed = idem.lookup(idempotencyKey);
        if (replayed == null) replayed = repo.findByIdempotencyKey(idempotencyKey);
        if (replayed != null) {
            idem.store(idempotencyKey, replayed);   // re-warm cache on DB hit
            return new Result(replayed, true);
        }

        Payment pending = savePending(idempotencyKey, req);

        PaymentGatewayResponse resp;
        try {
            resp = gatewayInvoker.charge(
                    new PaymentGatewayChargeRequest(pending.getPolicyNumber(),
                                                    pending.getAmount(),
                                                    pending.getCurrency()));
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (reason.length() > 250) reason = reason.substring(0, 250);
            Payment failed = saveFailure(pending.getId(), reason);
            // 1 initial + maxRetries=2 attempts = 3 calls total.
            dlq.publish(failed, reason, 3);
            LOG.log(Level.WARNING, "Payment " + failed.getId() + " failed after retries, dead-lettered", e);
            idem.store(idempotencyKey, failed);
            return new Result(failed, false);
        }

        Payment ok = saveSuccess(pending.getId(), resp.getExternalRef());
        LOG.info(() -> "Payment " + ok.getId() + " succeeded ext=" + resp.getExternalRef());
        idem.store(idempotencyKey, ok);
        return new Result(ok, false);
    }

    @Transactional
    Payment savePending(String idempotencyKey, PaymentRequest req) {
        Policy policy = policyRepo.findByNumber(req.policyNumber());
        if (policy == null) throw new NotFoundException("policy " + req.policyNumber());
        Payment p = new Payment();
        p.setPolicyNumber(req.policyNumber());
        p.setAmount(req.amount());
        p.setCurrency(req.currency() == null ? "USD" : req.currency());
        p.setIdempotencyKey(idempotencyKey);
        p.setStatus("PENDING");
        p.setCreatedAt(OffsetDateTime.now());
        return repo.save(p);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    Payment saveSuccess(Long id, String externalRef) {
        Payment p = repo.findById(id);
        p.setStatus("SUCCEEDED");
        p.setExternalRef(externalRef);
        p.setProcessedAt(OffsetDateTime.now());
        return repo.save(p);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    Payment saveFailure(Long id, String reason) {
        Payment p = repo.findById(id);
        p.setStatus("FAILED");
        p.setFailureReason(reason);
        p.setProcessedAt(OffsetDateTime.now());
        return repo.save(p);
    }

    public Payment getById(Long id) {
        return repo.findById(id);
    }

    public record Result(Payment payment, boolean replayed) {}
}
