package com.jtalk.livecommunication.repository;

import com.jtalk.livecommunication.config.VoiceRoomProperties;
import io.lettuce.core.LettuceFutures;
import io.lettuce.core.Range;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ZAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Who is in the room right now: ZSET {@code voiceroom:{id}:presence}, userId -> when they joined (epoch millis) until LiveKit
 * confirms their media connection, then {@link #CONNECTED}, which no staleness check ever trims. Someone who joined but never
 * connected ages out on read, so no sweeper is needed. Kicked users are kept in {@code voiceroom:{id}:banned}.
 */
@Singleton
public class PresenceRepository extends RedisRepository {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final double CONNECTED = Long.MAX_VALUE;

    private final Clock clock;
    private final Duration presenceTimeout;
    private final long ttlSeconds;

    public PresenceRepository(StatefulRedisConnection<String, String> connection, Clock clock, VoiceRoomProperties properties) {
        super(connection);
        this.clock = clock;
        this.presenceTimeout = properties.presenceTimeout();
        this.ttlSeconds = properties.stateTtl().toSeconds();
    }

    public void enter(UUID roomId, UUID userId) {
        RedisAsyncCommands<String, String> redis = connection.async();
        LettuceFutures.awaitAll(TIMEOUT,
                redis.zadd(key(roomId), ZAddArgs.Builder.gt(), clock.millis(), userId.toString()),
                redis.expire(key(roomId), ttlSeconds));
    }

    public boolean connected(UUID roomId, UUID userId) {
        RedisAsyncCommands<String, String> redis = connection.async();
        RedisFuture<Long> added = redis.zadd(key(roomId), CONNECTED, userId.toString());
        LettuceFutures.awaitAll(TIMEOUT, added, redis.expire(key(roomId), ttlSeconds));
        return added.toCompletableFuture().join() > 0;
    }

    public boolean remove(UUID roomId, UUID userId) {
        return connection.sync().zrem(key(roomId), userId.toString()) > 0;
    }

    public boolean isPresent(UUID roomId, UUID userId) {
        Double lastSeen = connection.sync().zscore(key(roomId), userId.toString());
        return lastSeen != null && lastSeen >= freshnessCutoff().toEpochMilli();
    }

    /** Users whose connection is confirmed or who joined recently; stale entries are trimmed first. */
    public List<UUID> findPresent(UUID roomId) {
        RedisCommands<String, String> redis = connection.sync();
        redis.zremrangebyscore(key(roomId), Range.create(Double.NEGATIVE_INFINITY, (double) freshnessCutoff().toEpochMilli() - 1));
        return redis.zrange(key(roomId), 0, -1).stream().map(UUID::fromString).toList();
    }

    /** Bars a kicked user from joining this room again. */
    public void ban(UUID roomId, UUID userId) {
        RedisCommands<String, String> redis = connection.sync();
        redis.sadd(bannedKey(roomId), userId.toString());
        redis.expire(bannedKey(roomId), ttlSeconds);
    }

    public boolean isBanned(UUID roomId, UUID userId) {
        return connection.sync().sismember(bannedKey(roomId), userId.toString());
    }

    public void clear(UUID roomId) {
        connection.sync().del(key(roomId), bannedKey(roomId));
    }

    /** A join that LiveKit has not confirmed within this long no longer counts as present. */
    Instant freshnessCutoff() {
        return clock.instant().minus(presenceTimeout);
    }

    private static String bannedKey(UUID roomId) {
        return roomKey(roomId, "banned");
    }

    static String key(UUID roomId) {
        return roomKey(roomId, "presence");
    }
}
