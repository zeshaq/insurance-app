package com.example.insurance.claim;

import com.example.insurance.audit.AuditEvent;
import com.example.insurance.audit.AuditPublisher;
import com.example.insurance.dashboard.AgentDashboardEvent;
import com.example.insurance.dashboard.AgentDashboardPublisher;
import com.example.insurance.policy.Policy;
import com.example.insurance.policy.PolicyRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class ClaimService {

    private static final Logger LOG = Logger.getLogger(ClaimService.class.getName());

    @Inject ClaimRepository       repo;
    @Inject PolicyRepository      policyRepo;
    @Inject MinioStorageService   storage;
    @Inject OcrInvoker            ocr;
    @Inject PartnerInvoker        partner;
    @Inject AgentDashboardPublisher dashboard;
    @Inject AuditPublisher        audit;
    @Inject ClaimEventPublisher   claimEvents;

    /**
     * Filing flow:
     *   1. Validate policy exists.
     *   2. Stream uploaded photo to MinIO (binary out of Postgres).
     *   3. Persist Claim row in FILED status with the MinIO key.
     *   4. Call MI's OCR mediator to extract text. Failure here doesn't
     *      block claim creation — the claim is filed regardless, OCR
     *      gets retried by an operator or a future batch job.
     */
    public Claim file(String policyNumber, String description,
                      InputStream content, long contentLength,
                      String contentType, String originalName,
                      String otherPartyVin) throws Exception {
        Policy policy = policyRepo.findByNumber(policyNumber);
        if (policy == null) throw new NotFoundException("policy " + policyNumber);

        String key = null;
        if (content != null) {
            key = storage.upload(content, contentLength, contentType, originalName);
        }
        Claim filed = saveFiled(policyNumber, description, key, contentType);

        Claim afterOcr = filed;
        if (key != null) {
            try {
                OcrResponse r = ocr.extract(new OcrRequest(key, contentType));
                if (r != null) {
                    afterOcr = saveOcr(filed.getId(), r.getText(), r.getConfidence());
                    final Claim ocrSnapshot = afterOcr;
                    final java.math.BigDecimal conf = r.getConfidence();
                    LOG.info(() -> "Claim " + ocrSnapshot.getId() + " OCR confidence=" + conf);
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "OCR failed for claim " + filed.getId() + " — claim remains FILED without OCR", e);
            }
        }

        // Partner lookup is best-effort: cross-carrier APIs are flaky and
        // we'd rather record the claim with no partner data than fail the
        // whole filing because RivalInsurance is rotating their certs.
        if (otherPartyVin != null && !otherPartyVin.isBlank()) {
            try {
                PartnerResponse p = partner.lookup(otherPartyVin);
                if (p != null && p.isCovers()) {
                    afterOcr = savePartner(afterOcr.getId(), otherPartyVin,
                            p.getPolicyNumber(), p.getCarrier());
                    final Claim partnerSnapshot = afterOcr;
                    final String partnerCarrier = p.getCarrier();
                    LOG.info(() -> "Claim " + partnerSnapshot.getId() + " partner=" + partnerCarrier);
                } else {
                    afterOcr = savePartner(afterOcr.getId(), otherPartyVin, null, null);
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Partner lookup failed for claim " + filed.getId() + " — recording the VIN only", e);
                try { afterOcr = savePartner(afterOcr.getId(), otherPartyVin, null, null); }
                catch (Exception ignore) {}
            }
        }
        publishToDashboard(afterOcr);
        publishToAuditAndClaimEvents(afterOcr, "FILED");
        return afterOcr;
    }

    /** Approve hook: flips claim status to APPROVED and re-publishes audit+claim-events. */
    public Claim approve(Long id) {
        Claim updated = saveApproved(id);
        publishToDashboard(updated);
        publishToAuditAndClaimEvents(updated, "APPROVED");
        return updated;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    Claim saveApproved(Long id) {
        Claim c = repo.findById(id);
        if (c == null) throw new NotFoundException("claim " + id);
        c.setStatus("APPROVED");
        return repo.save(c);
    }

    private void publishToAuditAndClaimEvents(Claim c, String action) {
        try {
            String state = String.format(
                    "{\"id\":%d,\"policyNumber\":\"%s\",\"status\":\"%s\"}",
                    c.getId(), c.getPolicyNumber(), c.getStatus());
            audit.publish(new AuditEvent("claim", String.valueOf(c.getId()), action,
                    "system", state, java.time.OffsetDateTime.now().toString()));
            claimEvents.publish(c.getId(), action, c);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "audit/claim-events publish failed for claim " + c.getId(), e);
        }
    }

    /**
     * Fire the live dashboard event AFTER ocr + partner enrichment so the
     * agent feed shows the fully-populated claim, not the bare PENDING row.
     * Best-effort: if Redis is down the claim still files cleanly.
     */
    private void publishToDashboard(Claim c) {
        try {
            String snippet = c.getOcrText() == null ? null
                    : (c.getOcrText().length() > 80 ? c.getOcrText().substring(0, 80) + "…" : c.getOcrText());
            dashboard.publish(new AgentDashboardEvent(
                    "CLAIM_FILED",
                    c.getId(),
                    c.getPolicyNumber(),
                    c.getDescription(),
                    snippet,
                    c.getOtherPartyCarrier(),
                    c.getFiledAt() == null ? null : c.getFiledAt().toString()));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Dashboard publish failed for claim " + c.getId() + " — UI may miss this event", e);
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    Claim savePartner(Long id, String otherVin, String otherPolicy, String otherCarrier) {
        Claim c = repo.findById(id);
        c.setOtherPartyVin(otherVin);
        c.setOtherPartyPolicy(otherPolicy);
        c.setOtherPartyCarrier(otherCarrier);
        return repo.save(c);
    }

    @Transactional
    Claim saveFiled(String policyNumber, String description, String photoKey, String contentType) {
        Claim c = new Claim();
        c.setPolicyNumber(policyNumber);
        c.setDescription(description);
        c.setPhotoKey(photoKey);
        c.setPhotoContentType(contentType);
        c.setStatus("FILED");
        c.setFiledAt(OffsetDateTime.now());
        return repo.save(c);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    Claim saveOcr(Long id, String text, BigDecimal confidence) {
        Claim c = repo.findById(id);
        c.setOcrText(text);
        c.setOcrConfidence(confidence);
        return repo.save(c);
    }

    public Claim getById(Long id) {
        Claim c = repo.findById(id);
        if (c == null) {
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
        return c;
    }
}
