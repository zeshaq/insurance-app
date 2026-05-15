package com.example.insurance.audit;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory projection of {@code audit-events}. Rebuilt from the start of
 * the topic on every Liberty boot — that's why audit-events is compacted:
 * the rebuild reads exactly one record per entity instead of replaying
 * every state change since dawn.
 *
 * No backing DB row. The pedagogical point is that a compacted topic is
 * effectively a key-value store, and you can recover the projection by
 * re-reading the topic — durability comes from Kafka.
 */
@ApplicationScoped
public class AuditSnapshot {

    private final ConcurrentMap<String, AuditEvent> latest = new ConcurrentHashMap<>();

    /** Called by AuditConsumer for every record (latest write wins). */
    public void put(AuditEvent e) {
        latest.put(e.key(), e);
    }

    public AuditEvent get(String entityType, String entityId) {
        return latest.get(entityType + ":" + entityId);
    }

    public Collection<AuditEvent> all() {
        return Collections.unmodifiableCollection(latest.values());
    }

    public int size() {
        return latest.size();
    }
}
