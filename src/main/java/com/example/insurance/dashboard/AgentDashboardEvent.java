package com.example.insurance.dashboard;

/**
 * Live-feed payload pushed to the agent dashboard. The same JSON goes onto
 * the Redis Pub/Sub channel (for live fan-out) and the Redis Stream (for
 * durable backlog). Late-joining dashboards read the last N stream entries
 * on connect to bootstrap their view.
 *
 * type values: CLAIM_FILED (slice 11 only — future slices will add
 * PAYMENT_FAILED, FRAUD_ALERT, etc.)
 */
public record AgentDashboardEvent(
        String type,
        Long claimId,
        String policyNumber,
        String description,
        String ocrSnippet,
        String otherPartyCarrier,
        String filedAt
) {}
