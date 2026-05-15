package com.example.insurance.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "notification")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_topic", nullable = false)
    private String eventTopic;

    @Column(name = "event_key")
    private String eventKey;

    @Column(nullable = false)
    private String channel;

    @Column(nullable = false)
    private String recipient;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(nullable = false)
    private String status;

    @Column(name = "external_ref")
    private String externalRef;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "dispatched_at")
    private OffsetDateTime dispatchedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEventTopic() { return eventTopic; }
    public void setEventTopic(String t) { this.eventTopic = t; }
    public String getEventKey() { return eventKey; }
    public void setEventKey(String k) { this.eventKey = k; }
    public String getChannel() { return channel; }
    public void setChannel(String c) { this.channel = c; }
    public String getRecipient() { return recipient; }
    public void setRecipient(String r) { this.recipient = r; }
    public String getSubject() { return subject; }
    public void setSubject(String s) { this.subject = s; }
    public String getBody() { return body; }
    public void setBody(String b) { this.body = b; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
    public String getExternalRef() { return externalRef; }
    public void setExternalRef(String r) { this.externalRef = r; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String r) { this.failureReason = r; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime t) { this.createdAt = t; }
    public OffsetDateTime getDispatchedAt() { return dispatchedAt; }
    public void setDispatchedAt(OffsetDateTime t) { this.dispatchedAt = t; }
}
