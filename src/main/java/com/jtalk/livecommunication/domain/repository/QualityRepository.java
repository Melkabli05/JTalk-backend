package com.jtalk.livecommunication.domain.repository;

import com.jtalk.livecommunication.config.VoiceRoomProperties;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.inject.Singleton;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Users currently on the fallback mix: hash {@code voiceroom:{id}:degraded}, userId -> start (epoch millis)
 * of the current healthy streak, or 0 while the connection is still bad.
 */
@Singleton
public class QualityRepository {

    private final StatefulRedisConnection<String, String> connection;
    private final long ttlSeconds;

    public QualityRepository(StatefulRedisConnection<String, String> connection, VoiceRoomProperties properties) {
        this.connection = connection;
        this.ttlSeconds = properties.stateTtl().toSeconds();
    }

    /** Marks the user degraded and resets any healthy streak. */
    public void markDegraded(UUID roomId, String userId) {
        RedisCommands<String, String> redis = connection.sync();
        redis.hset(key(roomId), userId, "0");
        redis.expire(key(roomId), ttlSeconds);
    }

    public boolean isDegraded(UUID roomId, String userId) {
        return connection.sync().hexists(key(roomId), userId);
    }

    /** Starts the healthy streak if needed. Returns its start in epoch millis, or -1 if the user is not degraded. */
    public long markHealthy(UUID roomId, String userId, long nowMillis) {
        RedisCommands<String, String> redis = connection.sync();
        String streakStart = redis.hget(key(roomId), userId);
        if (streakStart == null) {
            return -1;
        }
        if (Long.parseLong(streakStart) == 0) {
            redis.hset(key(roomId), userId, Long.toString(nowMillis));
            return nowMillis;
        }
        return Long.parseLong(streakStart);
    }

    /** Removes the user and returns how many users remain degraded. */
    public long remove(UUID roomId, String userId) {
        RedisCommands<String, String> redis = connection.sync();
        redis.hdel(key(roomId), userId);
        return redis.hlen(key(roomId));
    }

    /** Remembers tracks the server unsubscribed this user from, so they can be restored after recovery. */
    public void addPruned(UUID roomId, String userId, Collection<String> trackSids) {
        if (trackSids.isEmpty()) {
            return;
        }
        RedisCommands<String, String> redis = connection.sync();
        redis.sadd(prunedKey(roomId, userId), trackSids.toArray(String[]::new));
        redis.expire(prunedKey(roomId, userId), ttlSeconds);
    }

    /** Returns and forgets the tracks pruned for this user. */
    public Set<String> takePruned(UUID roomId, String userId) {
        RedisCommands<String, String> redis = connection.sync();
        Set<String> pruned = redis.smembers(prunedKey(roomId, userId));
        redis.del(prunedKey(roomId, userId));
        return pruned;
    }

    public void clear(UUID roomId) {
        RedisCommands<String, String> redis = connection.sync();
        redis.hkeys(key(roomId)).forEach(userId -> redis.del(prunedKey(roomId, userId)));
        redis.del(key(roomId));
    }

    private static String prunedKey(UUID roomId, String userId) {
        return "voiceroom:{" + roomId + "}:pruned:" + userId;
    }

    private static String key(UUID roomId) {
        return "voiceroom:{" + roomId + "}:degraded";
    }
}
