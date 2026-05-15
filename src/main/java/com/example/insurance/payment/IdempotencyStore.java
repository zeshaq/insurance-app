package com.example.insurance.payment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;

/**
 * Stores idempotency-keyed Payment snapshots in Redis for 24 hours — long
 * enough that a retrying client (mobile network drop, browser navigation
 * mid-submit, etc.) gets the same answer instead of double-charging.
 *
 * Key shape: {@code idempotency:payment:<client-supplied-key>}. The Payment
 * is stored as JSON; deserializing returns the same shape the client saw
 * the first time. The DB's UNIQUE(idempotency_key) is the source of truth
 * if this cache evicts early.
 */
@ApplicationScoped
public class IdempotencyStore {

    private static final Duration TTL = Duration.ofHours(24);

    @Inject
    RedisCommands<String, String> redis;

    private final Jsonb jsonb = JsonbBuilder.create();

    public void store(String key, Payment p) {
        redis.set("idempotency:payment:" + key, jsonb.toJson(p),
                  SetArgs.Builder.px(TTL.toMillis()));
    }

    public Payment lookup(String key) {
        String json = redis.get("idempotency:payment:" + key);
        return json == null ? null : jsonb.fromJson(json, Payment.class);
    }
}
