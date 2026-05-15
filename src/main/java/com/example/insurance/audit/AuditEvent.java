package com.example.insurance.audit;

/**
 * Audit-trail payload. Every state-changing operation in the domain emits
 * one of these onto the {@code audit-events} compacted topic. Keyed by
 * {@code entityType:entityId} (e.g., "claim:42") so log compaction collapses
 * multiple updates of the same entity down to the last write — the topic
 * becomes a "current state" projection without any database query.
 *
 * The state field is the JSON snapshot of the entity at write time; on a
 * compacted topic, replaying from the beginning yields exactly one record
 * per entity, holding its current state. That's the whole pedagogical
 * point of compaction — contrast with {@code claim-events} which is
 * retention-based and keeps every transition.
 */
public record AuditEvent(
        String entityType,
        String entityId,
        String action,
        String actor,
        String stateJson,
        String timestamp
) {
    /** Compaction key: same key collapses to one record after the log cleaner runs. */
    public String key() { return entityType + ":" + entityId; }
}
