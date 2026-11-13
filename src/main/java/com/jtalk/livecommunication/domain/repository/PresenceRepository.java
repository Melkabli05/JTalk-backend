package com.jtalk.livecommunication.domain.repository;

import com.jtalk.livecommunication.config.VoiceRoomProperties;
import io.lettuce.core.LettuceFutures;
import io.lettuce.core.Range;
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
 * Who is in the room right now: ZSET {@code voiceroom:{id}:presence}, userId -> last heartbeat (epoch millis). Staleness
 * is evaluated on read, so no sweeper is needed. Kicked users are kept in {@code voiceroom:{id}:banned}.
 */
@Singleton
public class PresenceRepository extends RedisRepository {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final Clock clock;
    private final Duration presenceTimeout;
    private final long ttlSeconds;

    public PresenceRepository(StatefulRedisConnection<String, String> connection, Clock clock, VoiceRoomProperties properties) {
        super(connection);
        this.clock = clock;
        this.presenceTimeout = properties.presenceTimeout();
        this.ttlSeconds = properties.stateTtl().toSeconds();
    }

    /** The heartbeat, sent by every participant every few seconds: both commands go out together, one round trip. */
    public void touch(UUID roomId, UUID userId) {
        RedisAsyncCommands<String, String> redis = connection.async();
        LettuceFutures.awaitAll(TIMEOUT,
                redis.zadd(key(roomId), clock.millis(), userId.toString()),
                redis.expire(key(roomId), ttlSeconds));
    }

    public void remove(UUID roomId, UUID userId) {
        connection.sync().zrem(key(roomId), userId.toString());
    }

    public boolean isPresent(UUID roomId, UUID userId) {
        Double lastSeen = connection.sync().zscore(key(roomId), userId.toString());
        return lastSeen != null && lastSeen >= freshnessCutoff().toEpochMilli();
    }

    /** Users with a recent heartbeat; stale entries are trimmed first. */
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

    /** A heartbeat older than this no longer counts as present. */
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
