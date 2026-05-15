package com.example.insurance.quote;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;

/**
 * Sliding-window rate limiter backed by a Redis sorted set.
 *
 * Each request is stored as a member of {@code ratelimit:<key>} with its
 * timestamp as the score. On each call we drop entries older than the window,
 * count what's left, and either reject (>= limit) or admit (and ZADD the new
 * timestamp).
 *
 * Per ADR 0005 the key for the quote endpoint is
 * {@code ratelimit:quote:{customer_id}}; until we wire identity in slice 5 we
 * key by {@code vehicleVin} as a stand-in.
 */
@ApplicationScoped
public class RateLimiter {

    @Inject
    RedisCommands<String, String> redis;

    public boolean allow(String key, int limit, Duration window) {
        long now = System.currentTimeMillis();
        long windowStart = now - window.toMillis();

        // Drop entries with score < windowStart (strictly older than the window).
        redis.zremrangebyscore(key, Double.NEGATIVE_INFINITY, (double) (windowStart - 1));

        long current = redis.zcard(key);
        if (current >= limit) {
            return false;
        }

        // Member uniqueness matters less than score (timestamp); add a small
        // suffix so concurrent millis-equal requests aren't collapsed.
        redis.zadd(key, (double) now, now + ":" + Math.random());

        // Keep the key from outliving the window forever.
        redis.expire(key, window.toSeconds() + 10);
        return true;
    }
}
