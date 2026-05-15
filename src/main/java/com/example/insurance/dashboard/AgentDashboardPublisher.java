package com.example.insurance.dashboard;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import io.lettuce.core.XAddArgs;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dual-channel publisher: every dashboard event lands on BOTH
 *   1. Redis Pub/Sub channel {@code dashboard:claims} — fan-out to every
 *      currently-connected WebSocket session in real time. No history;
 *      a client that wasn't connected when the message published misses it.
 *   2. Redis Stream {@code dashboard:stream} via XADD with MAXLEN ~200 —
 *      durable, capped backlog. A dashboard that connects later replays
 *      the last 20 entries to bootstrap its view.
 *
 * Pub/Sub is the "phone ringing" channel; Streams is the "voicemail" log.
 * Together they give the dashboard both live latency and survives-a-tab-
 * refresh durability without a database query.
 */
@ApplicationScoped
public class AgentDashboardPublisher {

    private static final Logger LOG = Logger.getLogger(AgentDashboardPublisher.class.getName());

    public static final String CHANNEL = "dashboard:claims";
    public static final String STREAM  = "dashboard:stream";
    private static final long  STREAM_MAXLEN = 200L;

    @Inject
    RedisCommands<String, String> redis;

    private final Jsonb jsonb = JsonbBuilder.create();

    public void publish(AgentDashboardEvent event) {
        String json = jsonb.toJson(event);
        try {
            redis.publish(CHANNEL, json);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Pub/Sub publish failed for " + event.claimId(), e);
        }
        try {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("payload", json);
            redis.xadd(STREAM, XAddArgs.Builder.maxlen(STREAM_MAXLEN).approximateTrimming(), entry);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "XADD failed for " + event.claimId(), e);
        }
    }
}
