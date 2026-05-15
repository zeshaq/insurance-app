package com.example.insurance.claim;

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
                      String contentType, String originalName) throws Exception {
        Policy policy = policyRepo.findByNumber(policyNumber);
        if (policy == null) throw new NotFoundException("policy " + policyNumber);

        String key = null;
        if (content != null) {
            key = storage.upload(content, contentLength, contentType, originalName);
        }
        Claim filed = saveFiled(policyNumber, description, key, contentType);

        if (key != null) {
            try {
                OcrResponse r = ocr.extract(new OcrRequest(key, contentType));
                if (r != null) {
                    Claim updated = saveOcr(filed.getId(), r.getText(), r.getConfidence());
                    LOG.info(() -> "Claim " + updated.getId() + " OCR confidence=" + r.getConfidence());
                    return updated;
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "OCR failed for claim " + filed.getId() + " — claim remains FILED without OCR", e);
            }
        }
        return filed;
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
