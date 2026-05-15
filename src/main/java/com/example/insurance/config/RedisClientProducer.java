package com.example.insurance.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.logging.Logger;

/**
 * Connect to Redis on the shared `insurance-net` podman network (the container
 * name `redis` resolves via podman's embedded DNS) and produce a singleton
 * {@link RedisCommands} for injection.
 *
 * One connection is shared by every injection point — Lettuce connections are
 * thread-safe and multiplexed. If a future slice needs blocking commands or
 * pub/sub, request a separate connection rather than reusing this one.
 */
@ApplicationScoped
public class RedisClientProducer {

    private static final Logger LOG = Logger.getLogger(RedisClientProducer.class.getName());

    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;

    @PostConstruct
    void init() {
        client = RedisClient.create("redis://redis:6379");
        connection = client.connect();
        LOG.info("Redis client connected to redis:6379");
    }

    @Produces
    @ApplicationScoped
    RedisCommands<String, String> commands() {
        return connection.sync();
    }

    @PreDestroy
    void close() {
        if (connection != null) connection.close();
        if (client != null) client.shutdown();
    }
}
