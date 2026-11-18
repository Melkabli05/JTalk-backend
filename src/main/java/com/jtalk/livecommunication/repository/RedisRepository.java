package com.jtalk.livecommunication.repository;

import io.lettuce.core.LettuceFutures;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulRedisConnection;

import java.time.Duration;
import java.util.UUID;

/**
 * What every Redis-backed repository shares: the connection, and the one definition of how a room's keys are named.
 * The {@code {roomId}} hash tag puts all of a room's keys in the same cluster slot, which is what lets the Lua scripts
 * and multi-key commands touch them together, so it must never be spelled by hand anywhere else.
 */
abstract class RedisRepository {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    protected final StatefulRedisConnection<String, String> connection;

    protected RedisRepository(StatefulRedisConnection<String, String> connection) {
        this.connection = connection;
    }

    /**
     * Waits for commands that were sent without waiting (pipelined): they share one round trip. The connection is shared between threads,
     * so batching is always "everything in flight, then wait", never switching auto-flush off, which would stall every other request.
     */
    static void await(RedisFuture<?>... futures) {
        if (!LettuceFutures.awaitAll(TIMEOUT, futures)) {
            throw new RedisCommandTimeoutException("Redis did not answer within " + TIMEOUT);
        }
    }

    static String roomKey(UUID roomId, String part) {
        return "voiceroom:{" + roomId + "}:" + part;
    }
}
