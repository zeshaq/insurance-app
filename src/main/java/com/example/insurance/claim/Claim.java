package com.example.insurance.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "claim")
public class Claim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "photo_key")
    private String photoKey;

    @Column(name = "photo_content_type")
    private String photoContentType;

    @Column(name = "ocr_text", columnDefinition = "text")
    private String ocrText;

    @Column(name = "ocr_confidence")
    private BigDecimal ocrConfidence;

    @Column(nullable = false)
    private String status;

    @Column(name = "filed_at", nullable = false)
    private OffsetDateTime filedAt;

    @Column(name = "other_party_vin")
    private String otherPartyVin;

    @Column(name = "other_party_policy")
    private String otherPartyPolicy;

    @Column(name = "other_party_carrier")
    private String otherPartyCarrier;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPolicyNumber() { return policyNumber; }
    public void setPolicyNumber(String s) { this.policyNumber = s; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public String getPhotoKey() { return photoKey; }
    public void setPhotoKey(String k) { this.photoKey = k; }
    public String getPhotoContentType() { return photoContentType; }
    public void setPhotoContentType(String c) { this.photoContentType = c; }
    public String getOcrText() { return ocrText; }
    public void setOcrText(String t) { this.ocrText = t; }
    public BigDecimal getOcrConfidence() { return ocrConfidence; }
    public void setOcrConfidence(BigDecimal c) { this.ocrConfidence = c; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
    public OffsetDateTime getFiledAt() { return filedAt; }
    public void setFiledAt(OffsetDateTime t) { this.filedAt = t; }
    public String getOtherPartyVin() { return otherPartyVin; }
    public void setOtherPartyVin(String v) { this.otherPartyVin = v; }
    public String getOtherPartyPolicy() { return otherPartyPolicy; }
    public void setOtherPartyPolicy(String p) { this.otherPartyPolicy = p; }
    public String getOtherPartyCarrier() { return otherPartyCarrier; }
    public void setOtherPartyCarrier(String c) { this.otherPartyCarrier = c; }
}
