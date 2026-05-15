package com.example.insurance.policy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "policy")
public class Policy {

    @Id
    @Column(name = "policy_number", length = 20)
    private String policyNumber;

    @Column(name = "quote_id", nullable = false, unique = true)
    private Long quoteId;

    @Column(nullable = false)
    private String status;

    @Column(name = "bound_at", nullable = false)
    private OffsetDateTime boundAt;

    public String getPolicyNumber() { return policyNumber; }
    public void setPolicyNumber(String policyNumber) { this.policyNumber = policyNumber; }

    public Long getQuoteId() { return quoteId; }
    public void setQuoteId(Long quoteId) { this.quoteId = quoteId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public OffsetDateTime getBoundAt() { return boundAt; }
    public void setBoundAt(OffsetDateTime boundAt) { this.boundAt = boundAt; }
}
