package com.example.insurance.dashboard;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;

import io.lettuce.core.RedisClient;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;

import java.util.logging.Logger;

/**
 * Lettuce pub/sub side of the dashboard. A dedicated connection is required —
 * the existing {@code RedisCommands<String,String>} runs sync commands and
 * cannot be in subscribed mode at the same time. So we open our own
 * {@link RedisClient}, register a listener, and fan messages out to whichever
 * {@link AgentDashboardSocket} sessions are currently connected.
 *
 * Started via CDI {@code @Initialized(ApplicationScoped.class)} observer
 * because {@link ApplicationScoped} beans are lazy-init — same trick as
 * NotificationConsumer in slice 8.
 */
@ApplicationScoped
public class AgentDashboardSubscriber {

    private static final Logger LOG = Logger.getLogger(AgentDashboardSubscriber.class.getName());

    private RedisClient client;
    private StatefulRedisPubSubConnection<String, String> connection;

    void start(@Observes @Initialized(ApplicationScoped.class) Object init) {
        client = RedisClient.create("redis://redis:6379");
        connection = client.connectPubSub();
        connection.addListener(new RedisPubSubAdapter<String, String>() {
            @Override
            public void message(String channel, String payload) {
                if (AgentDashboardPublisher.CHANNEL.equals(channel)) {
                    AgentDashboardSocket.broadcast(payload);
                }
            }
        });
        connection.sync().subscribe(AgentDashboardPublisher.CHANNEL);
        LOG.info("AgentDashboardSubscriber connected to Redis pub/sub, channel " + AgentDashboardPublisher.CHANNEL);
    }

    @PreDestroy
    void stop() {
        try { if (connection != null) connection.close(); } catch (Exception ignored) {}
        try { if (client != null) client.shutdown(); } catch (Exception ignored) {}
    }
}
