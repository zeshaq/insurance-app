package com.example.insurance.audit;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Issue #52 — contrast() used Map.of(...) which throws NPE on null values,
 * so requesting an unknown claim id 500'd. These tests pin the new contract:
 * 404 when nothing exists; otherwise a Response with HashMap-allowed nulls.
 */
class AuditContrastTest {

    private AuditSnapshot snapshot;

    /** Test subclass overrides the Kafka read to keep the unit test offline. */
    private AuditResource resourceReturning(List<Map<String, Object>> stubbedEvents) {
        AuditResource res = new AuditResource() {
            @Override
            protected List<Map<String, Object>> readClaimEvents(String claimId) {
                return stubbedEvents;
            }
        };
        res.snapshot = snapshot;
        return res;
    }

    @BeforeEach
    void setup() {
        snapshot = Mockito.mock(AuditSnapshot.class);
    }

    @Test
    void returns_404_when_snapshot_and_events_both_empty() {
        when(snapshot.get("claim", "0")).thenReturn(null);
        AuditResource res = resourceReturning(List.of());

        assertThatThrownBy(() -> res.contrast("0"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("0");
    }

    @Test
    void returns_200_with_null_snapshot_when_events_exist() {
        when(snapshot.get("claim", "42")).thenReturn(null);
        AuditResource res = resourceReturning(List.of(Map.of("action", "FILED")));

        Response r = res.contrast("42");

        assertThat(r.getStatus()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) r.getEntity();
        assertThat(body.get("claimId")).isEqualTo("42");
        assertThat(body.get("snapshot")).isNull();
        assertThat(body.get("events")).isInstanceOf(List.class);
    }

    @Test
    void returns_200_with_snapshot_and_events() {
        AuditEvent snap = new AuditEvent("claim", "42", "APPROVED", "tester", "{\"id\":42}", "2026-05-17T00:00:00Z");
        when(snapshot.get("claim", "42")).thenReturn(snap);
        AuditResource res = resourceReturning(List.of(
                Map.of("action", "FILED"),
                Map.of("action", "APPROVED")));

        Response r = res.contrast("42");

        assertThat(r.getStatus()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) r.getEntity();
        assertThat(body.get("snapshot")).isSameAs(snap);
        @SuppressWarnings("unchecked")
        var events = (List<Map<String, Object>>) body.get("events");
        assertThat(events).hasSize(2);
    }
}
