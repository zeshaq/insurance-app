package com.example.insurance.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "report_run")
public class ReportRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "succeeded_count", nullable = false)
    private Long succeededCount;

    @Column(name = "failed_count", nullable = false)
    private Long failedCount;

    @Column(name = "unknown_count", nullable = false)
    private Long unknownCount;

    @Column(nullable = false)
    private String source;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSucceededCount() { return succeededCount; }
    public void setSucceededCount(Long v) { this.succeededCount = v; }
    public Long getFailedCount() { return failedCount; }
    public void setFailedCount(Long v) { this.failedCount = v; }
    public Long getUnknownCount() { return unknownCount; }
    public void setUnknownCount(Long v) { this.unknownCount = v; }
    public String getSource() { return source; }
    public void setSource(String s) { this.source = s; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime t) { this.createdAt = t; }
}
