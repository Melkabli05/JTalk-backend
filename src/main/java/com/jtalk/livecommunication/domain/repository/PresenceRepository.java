package com.jtalk.livecommunication.domain.repository;

import com.jtalk.livecommunication.config.VoiceRoomProperties;
import io.lettuce.core.Range;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Presence as a ZSET {@code voiceroom:{id}:presence}: userId -> last heartbeat (epoch millis).
 * Staleness is evaluated on read, so no sweeper is needed; stale entries are trimmed opportunistically.
 */
@Singleton
public class PresenceRepository {

    private final StatefulRedisConnection<String, String> connection;
    private final long ttlSeconds;

    public PresenceRepository(StatefulRedisConnection<String, String> connection,
                              VoiceRoomProperties properties) {
        this.connection = connection;
        this.ttlSeconds = properties.stateTtl().toSeconds();
    }

    public void touch(UUID roomId, String userId, Instant now) {
        RedisCommands<String, String> redis = connection.sync();
        String key = key(roomId);
        redis.zadd(key, now.toEpochMilli(), userId);
        redis.expire(key, ttlSeconds);
    }

    public void remove(UUID roomId, String userId) {
        connection.sync().zrem(key(roomId), userId);
    }

    public List<String> findPresent(UUID roomId, Instant notBefore) {
        RedisCommands<String, String> redis = connection.sync();
        // Drop stale entries first so the set cannot grow with users that vanished without leaving.
        redis.zremrangebyscore(key(roomId), Range.create(Double.NEGATIVE_INFINITY, (double) notBefore.toEpochMilli() - 1));
        return redis.zrange(key(roomId), 0, -1);
    }

    public boolean isPresent(UUID roomId, String userId, Instant notBefore) {
        Double lastSeen = connection.sync().zscore(key(roomId), userId);
        return lastSeen != null && lastSeen >= notBefore.toEpochMilli();
    }

    /** Bars a kicked user from joining this room again. */
    public void ban(UUID roomId, String userId) {
        RedisCommands<String, String> redis = connection.sync();
        redis.sadd(bannedKey(roomId), userId);
        redis.expire(bannedKey(roomId), ttlSeconds);
    }

    public boolean isBanned(UUID roomId, String userId) {
        return connection.sync().sismember(bannedKey(roomId), userId);
    }

    public void clear(UUID roomId) {
        connection.sync().del(key(roomId), bannedKey(roomId));
    }

    private static String bannedKey(UUID roomId) {
        return "voiceroom:{" + roomId + "}:banned";
    }

    static String key(UUID roomId) {
        return "voiceroom:{" + roomId + "}:presence";
    }
}
