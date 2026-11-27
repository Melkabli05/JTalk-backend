package com.jtalk.livecommunication.repository;

import com.jtalk.livecommunication.config.VoiceRoomProperties;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ZAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Who is in the room right now: ZSET {@code voiceroom:{id}:presence}, userId -> when they joined (epoch millis) until LiveKit
 * confirms their media connection, then {@link #CONNECTED}, which no staleness check ever trims. Someone who joined but never
 * connected ages out on read, so no sweeper is needed. Kicked users are kept in {@code voiceroom:{id}:banned}.
 */
@Singleton
public class PresenceRepository extends RedisRepository {

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

    public boolean connected(UUID roomId, UUID userId) {
        RedisAsyncCommands<String, String> redis = connection.async();
        RedisFuture<Long> added = redis.zadd(key(roomId), CONNECTED, userId.toString());
        await(added, redis.expire(key(roomId), ttlSeconds));
        return added.toCompletableFuture().join() > 0;
    }

    public boolean remove(UUID roomId, UUID userId) {
        return connection.sync().zrem(key(roomId), userId.toString()) > 0;
    }

    public boolean isPresent(UUID roomId, UUID userId) {
        Double lastSeen = connection.sync().zscore(key(roomId), userId.toString());
        return lastSeen != null && lastSeen >= freshnessCutoff().toEpochMilli();
    }

    /**
     * Returns up to {@code limitPerRoom} currently-present user IDs per room in one pipelined Redis round trip.
     */
    public Map<UUID, List<UUID>> findPresentUsers(Collection<UUID> roomIds, int limitPerRoom) {
        if (roomIds.isEmpty() || limitPerRoom <= 0) {
            return Map.of();
        }
        Range<Double> active = Range.create((double) freshnessCutoff().toEpochMilli(), Double.POSITIVE_INFINITY);
        Limit limit = Limit.create(0, limitPerRoom);
        RedisAsyncCommands<String, String> redis = connection.async();
        Map<UUID, RedisFuture<List<String>>> pending = new LinkedHashMap<>();
        for (UUID roomId : roomIds) {
            pending.put(roomId, redis.zrevrangebyscore(key(roomId), active, limit));
        }
        await(pending.values().toArray(RedisFuture[]::new));

        Map<UUID, List<UUID>> result = new LinkedHashMap<>();
        pending.forEach((roomId, future) -> result.put(roomId,
                future.toCompletableFuture().join().stream().map(UUID::fromString).toList()));
        return result;
    }

    /**
     * Finds the first active room {@code userId} is currently present in, in one pipelined Redis round trip.
     */
    public Optional<UUID> findActiveRoomForUser(Collection<UUID> activeRoomIds, UUID userId) {
        if (activeRoomIds.isEmpty()) {
            return Optional.empty();
        }
        String member = userId.toString();
        long cutoff = freshnessCutoff().toEpochMilli();
        RedisAsyncCommands<String, String> redis = connection.async();
        Map<UUID, RedisFuture<Double>> pending = new LinkedHashMap<>();
        for (UUID roomId : activeRoomIds) {
            pending.put(roomId, redis.zscore(key(roomId), member));
        }
        await(pending.values().toArray(RedisFuture[]::new));
        return pending.entrySet().stream()
                .filter(entry -> {
                    Double score = entry.getValue().toCompletableFuture().join();
                    return score != null && score >= cutoff;
                })
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /**
     * Finds the active room (if any) each requested user is currently present in, in one pipelined Redis round trip.
     */
    public Map<UUID, UUID> findActiveRoomsForUsers(Collection<UUID> activeRoomIds, Collection<UUID> userIds) {
        if (activeRoomIds.isEmpty() || userIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> distinctUsers = userIds.stream().distinct().toList();
        String[] members = distinctUsers.stream().map(UUID::toString).toArray(String[]::new);
        long cutoff = freshnessCutoff().toEpochMilli();

        RedisAsyncCommands<String, String> redis = connection.async();
        Map<UUID, RedisFuture<List<Double>>> pending = new LinkedHashMap<>();
        for (UUID roomId : activeRoomIds) {
            pending.put(roomId, redis.zmscore(key(roomId), members));
        }
        await(pending.values().toArray(RedisFuture[]::new));

        Map<UUID, UUID> result = new LinkedHashMap<>();
        pending.forEach((roomId, future) -> {
            List<Double> scores = future.toCompletableFuture().join();
            for (int i = 0; i < distinctUsers.size() && i < scores.size(); i++) {
                Double score = scores.get(i);
                if (score != null && score >= cutoff) {
                    result.putIfAbsent(distinctUsers.get(i), roomId);
                }
            }
        });
        return result;
    }

    /** Bars a kicked user from joining this room again. */
    public void ban(UUID roomId, UUID userId) {
        RedisAsyncCommands<String, String> redis = connection.async();
        await(redis.sadd(bannedKey(roomId), userId.toString()), redis.expire(bannedKey(roomId), ttlSeconds));
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

    static String bannedKey(UUID roomId) {
        return roomKey(roomId, "banned");
    }

    static String key(UUID roomId) {
        return roomKey(roomId, "presence");
    }
}
