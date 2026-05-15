package com.example.insurance.dashboard;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

import io.lettuce.core.Range;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * WebSocket endpoint for the agent dashboard live feed.
 *
 * On connect:
 *   - The client receives an {@code INITIAL_STATE} message holding the
 *     last 20 entries of the Redis Stream — bootstraps a late-joining
 *     dashboard without forcing it to query the DB.
 *   - The session is registered for live Pub/Sub fan-out.
 *
 * For slice 11 the endpoint is intentionally unauthenticated — wrapping
 * websocket-2.1 in mpJwt validation is straightforward (subprotocol or
 * query-param token) but out of scope here. Production deploys would
 * require a JWT in a query parameter or sec-websocket-protocol header.
 */
@ApplicationScoped
@ServerEndpoint("/ws/dashboard")
public class AgentDashboardSocket {

    private static final Logger LOG = Logger.getLogger(AgentDashboardSocket.class.getName());

    /** Visible to {@link AgentDashboardSubscriber} for broadcast. */
    private static final Set<Session> SESSIONS = new CopyOnWriteArraySet<>();

    private static final Jsonb JSONB = JsonbBuilder.create();

    @Inject
    RedisCommands<String, String> redis;

    @OnOpen
    public void onOpen(Session session) {
        SESSIONS.add(session);
        LOG.info(() -> "Dashboard WS connected: " + session.getId() + " (total=" + SESSIONS.size() + ")");
        try {
            String initial = JSONB.toJson(Map.of(
                    "type", "INITIAL_STATE",
                    "history", readRecentHistory()));
            session.getBasicRemote().sendText(initial);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to send INITIAL_STATE to " + session.getId(), e);
        }
    }

    @OnClose
    public void onClose(Session session) {
        SESSIONS.remove(session);
        LOG.info(() -> "Dashboard WS closed: " + session.getId() + " (total=" + SESSIONS.size() + ")");
    }

    @OnError
    public void onError(Session session, Throwable t) {
        LOG.log(Level.WARNING, "Dashboard WS error: " + session.getId(), t);
        SESSIONS.remove(session);
    }

    /**
     * Called by {@link AgentDashboardSubscriber} on every Pub/Sub message.
     * Static because the subscriber runs in a Lettuce thread that doesn't
     * have a CDI request context — keeping the broadcast surface a plain
     * static method avoids needing BeanManager dispatch from there.
     */
    static void broadcast(String message) {
        for (Session s : SESSIONS) {
            if (!s.isOpen()) continue;
            try {
                s.getBasicRemote().sendText(message);
            } catch (Exception e) {
                LOG.log(Level.FINE, "Drop session " + s.getId() + " — " + e.getMessage());
                SESSIONS.remove(s);
            }
        }
    }

    /** Read the last 20 stream entries (oldest first). */
    private List<String> readRecentHistory() {
        try {
            // XRANGE returns entries in chronological order; we want the last 20.
            List<StreamMessage<String, String>> rev =
                    redis.xrevrange(AgentDashboardPublisher.STREAM, Range.unbounded(),
                            io.lettuce.core.Limit.create(0, 20));
            // Reverse so the client receives them oldest-first.
            return rev.stream()
                    .map(m -> m.getBody().getOrDefault("payload", "{}"))
                    .toList()
                    .reversed();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Stream history read failed", e);
            return List.of();
        }
    }
}
