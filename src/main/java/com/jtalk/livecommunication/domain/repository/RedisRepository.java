package com.jtalk.livecommunication.domain.repository;

import io.lettuce.core.api.StatefulRedisConnection;

import java.util.UUID;

/**
 * What every Redis-backed repository shares: the connection, and the one definition of how a room's keys are named.
 * The {@code {roomId}} hash tag puts all of a room's keys in the same cluster slot, which is what lets the Lua scripts
 * and multi-key commands touch them together, so it must never be spelled by hand anywhere else.
 */
abstract class RedisRepository {

    protected final StatefulRedisConnection<String, String> connection;

    protected RedisRepository(StatefulRedisConnection<String, String> connection) {
        this.connection = connection;
    }

    static String roomKey(UUID roomId, String part) {
        return "voiceroom:{" + roomId + "}:" + part;
    }
}
