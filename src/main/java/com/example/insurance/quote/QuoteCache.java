package com.example.insurance.quote;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;

/**
 * Read-through cache for {@link Quote} keyed by id.
 *
 * Per ADR 0005: key pattern {@code quote:{id}}, TTL 15 minutes.
 *
 * Invalidation rule (the teachable one): every write path that touches a
 * Quote must call {@link #invalidate(Long)}. TTL is the belt; explicit
 * invalidation is the suspenders.
 */
@ApplicationScoped
public class QuoteCache {

    private static final Duration TTL = Duration.ofMinutes(15);

    @Inject
    RedisCommands<String, String> redis;

    private final Jsonb jsonb = JsonbBuilder.create();

    public Quote get(Long id) {
        String json = redis.get(key(id));
        if (json == null) return null;
        return jsonb.fromJson(json, Quote.class);
    }

    public void put(Quote q) {
        redis.setex(key(q.getId()), TTL.toSeconds(), jsonb.toJson(q));
    }

    public void invalidate(Long id) {
        redis.del(key(id));
    }

    private static String key(Long id) {
        return "quote:" + id;
    }
}
