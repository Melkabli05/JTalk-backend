package com.jtalk.livecommunication.domain.repository;

import com.jtalk.livecommunication.config.VoiceRoomProperties;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.inject.Singleton;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * The tracks the server unsubscribed each user from in low-bandwidth mode, so they can be restored:
 * {@code voiceroom:{id}:pruned:{userId}} (a set of track SIDs) plus {@code voiceroom:{id}:pruned-users} to find them all.
 */
@Singleton
public class PrunedTrackRepository extends RedisRepository {

    private final long ttlSeconds;

    public PrunedTrackRepository(StatefulRedisConnection<String, String> connection, VoiceRoomProperties properties) {
        super(connection);
        this.ttlSeconds = properties.stateTtl().toSeconds();
    }

    public void add(UUID roomId, UUID userId, Collection<String> trackSids) {
        if (trackSids.isEmpty()) {
            return;
        }
        RedisCommands<String, String> redis = connection.sync();
        redis.sadd(key(roomId, userId), trackSids.toArray(String[]::new));
        redis.sadd(usersKey(roomId), userId.toString());
        redis.expire(key(roomId, userId), ttlSeconds);
        redis.expire(usersKey(roomId), ttlSeconds);
    }

    /** Returns and forgets the tracks pruned for this user. */
    public Set<String> take(UUID roomId, UUID userId) {
        RedisCommands<String, String> redis = connection.sync();
        Set<String> pruned = redis.smembers(key(roomId, userId));
        redis.del(key(roomId, userId));
        redis.srem(usersKey(roomId), userId.toString());
        return pruned;
    }

    public void clear(UUID roomId) {
        RedisCommands<String, String> redis = connection.sync();
        redis.smembers(usersKey(roomId)).forEach(userId -> redis.del(key(roomId, UUID.fromString(userId))));
        redis.del(usersKey(roomId));
    }

    private static String key(UUID roomId, UUID userId) {
        return roomKey(roomId, "pruned:" + userId);
    }

    private static String usersKey(UUID roomId) {
        return roomKey(roomId, "pruned-users");
    }
}
