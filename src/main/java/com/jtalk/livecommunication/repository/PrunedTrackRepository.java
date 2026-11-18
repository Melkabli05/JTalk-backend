package com.jtalk.livecommunication.repository;

import com.jtalk.livecommunication.config.VoiceRoomProperties;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.async.RedisAsyncCommands;
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
        RedisAsyncCommands<String, String> redis = connection.async();
        await(redis.sadd(key(roomId, userId), trackSids.toArray(String[]::new)),
                redis.sadd(usersKey(roomId), userId.toString()),
                redis.expire(key(roomId, userId), ttlSeconds),
                redis.expire(usersKey(roomId), ttlSeconds));
    }

    /** Returns and forgets the tracks pruned for this user. */
    public Set<String> take(UUID roomId, UUID userId) {
        RedisAsyncCommands<String, String> redis = connection.async();
        RedisFuture<Set<String>> pruned = redis.smembers(key(roomId, userId));
        await(pruned, redis.del(key(roomId, userId)), redis.srem(usersKey(roomId), userId.toString()));
        return pruned.toCompletableFuture().join();
    }

    public void clear(UUID roomId) {
        RedisAsyncCommands<String, String> redis = connection.async();
        RedisFuture<Set<String>> users = redis.smembers(usersKey(roomId));
        await(users);
        RedisFuture<?>[] deletions = java.util.stream.Stream.concat(
                        users.toCompletableFuture().join().stream().map(userId -> redis.del(key(roomId, UUID.fromString(userId)))),
                        java.util.stream.Stream.of(redis.del(usersKey(roomId))))
                .toArray(RedisFuture[]::new);
        await(deletions);
    }

    private static String key(UUID roomId, UUID userId) {
        return roomKey(roomId, "pruned:" + userId);
    }

    private static String usersKey(UUID roomId) {
        return roomKey(roomId, "pruned-users");
    }
}
