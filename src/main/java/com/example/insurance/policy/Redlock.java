package com.example.insurance.policy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;
import java.util.UUID;

/**
 * Redlock — single-instance variant of the Redis distributed lock pattern.
 *
 * The classic Redlock recipe uses N independent Redis nodes and requires a
 * quorum of acquires; for a single Redis (our case) it collapses to:
 *
 *   acquire = SET key token NX PX ttl  (atomic check-and-set with expiry)
 *   release = Lua script that DELs only if the value still matches our token
 *
 * The Lua-scripted release is the load-bearing detail. Without it, a slow
 * caller whose lock has already expired could DEL a key now held by a *new*
 * acquirer — silently letting two binds proceed. The script compares the
 * token under the same Redis-thread atomicity and refuses to delete the
 * wrong holder's lock.
 *
 * Pedagogy: this is "good enough" for our demo because the consequence of
 * a missed lock is caught by the DB's UNIQUE(quote_id) constraint. A real
 * deployment with no DB backstop should run multi-node Redlock against an
 * odd-sized Redis cluster (or Redis Sentinel with fencing tokens).
 */
@ApplicationScoped
public class Redlock {

    private static final String RELEASE_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
          + "  return redis.call('del', KEYS[1]) "
          + "else "
          + "  return 0 "
          + "end";

    @Inject
    RedisCommands<String, String> redis;

    /** Returns a release-token on success, null if the lock is held. */
    public String tryAcquire(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        String r = redis.set(key, token, SetArgs.Builder.nx().px(ttl.toMillis()));
        return "OK".equals(r) ? token : null;
    }

    /** Releases only if our token still owns the key. Returns true on release. */
    public boolean release(String key, String token) {
        Object r = redis.eval(RELEASE_LUA, ScriptOutputType.INTEGER,
                              new String[]{key}, token);
        return r instanceof Long n && n == 1L;
    }
}
