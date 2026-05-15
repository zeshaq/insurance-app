package com.example.insurance.quote;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;
import java.util.logging.Logger;

/**
 * Caches the credit-bureau lookup so a busy quote endpoint doesn't hammer the
 * (paid, slow) external provider for the same VIN over and over. Same shape as
 * {@link QuoteCache} — read-through on a hit, populated by the caller on a
 * miss, TTL bounds staleness.
 *
 * TTL is 1 hour: short enough that a stale score doesn't persist through a
 * material change, long enough that a customer who refines their quote 5x in
 * a minute pays for one bureau call, not five. Per ADR 0005 the key is
 * {@code credit:{vin}}.
 */
@ApplicationScoped
public class CreditScoreCache {

    private static final Logger   LOG = Logger.getLogger(CreditScoreCache.class.getName());
    private static final Duration TTL = Duration.ofHours(1);

    @Inject
    RedisCommands<String, String> redis;

    private final Jsonb jsonb = JsonbBuilder.create();

    public CreditScore get(String vin) {
        try {
            String json = redis.get(key(vin));
            return json == null ? null : jsonb.fromJson(json, CreditScore.class);
        } catch (Exception e) {
            LOG.warning(() -> "Redis get failed for " + key(vin) + ": " + e.getMessage());
            return null;
        }
    }

    // Key by the *requested* VIN, not the response's vin field — the WireMock
    // stub (and presumably any real bureau's normalization) may echo back a
    // different value, and a cache that keys on the response wouldn't be hit
    // by subsequent lookups of the same input.
    public void put(String vin, CreditScore score) {
        if (vin == null || score == null) return;
        try {
            redis.setex(key(vin), TTL.toSeconds(), jsonb.toJson(score));
        } catch (Exception e) {
            LOG.warning(() -> "Redis setex failed for " + key(vin) + ": " + e.getMessage());
        }
    }

    private static String key(String vin) {
        return "credit:" + vin;
    }
}
