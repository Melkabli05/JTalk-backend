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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Who is in the room right now: ZSET {@code voiceroom:{id}:presence}, userId -> when they joined (epoch millis) until LiveKit
 * confirms their media connection, then {@link #CONNECTED}, which no staleness check ever trims. Someone who joined but never
 * connected ages out on read, so no sweeper is needed. Kicked users are kept in {@code voiceroom:{id}:banned}.
 * Also maintains {@link #USER_ROOM_KEY} for O(1) per-user active room lookup.
 */
@Singleton
public class PresenceRepository extends RedisRepository {

    private static final double CONNECTED = Long.MAX_VALUE;
    static final String USER_ROOM_KEY = "voiceroom:user-room";

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
        String uid = userId.toString();
        RedisFuture<Long> added = redis.zadd(key(roomId), CONNECTED, uid);
        await(added, redis.expire(key(roomId), ttlSeconds), redis.hset(USER_ROOM_KEY, uid, roomId.toString()));
        return added.toCompletableFuture().join() > 0;
    }

    public boolean remove(UUID roomId, UUID userId) {
        RedisAsyncCommands<String, String> redis = connection.async();
        String uid = userId.toString();
        RedisFuture<Long> removed = redis.zrem(key(roomId), uid);
        await(removed, redis.hdel(USER_ROOM_KEY, uid));
        return removed.toCompletableFuture().join() > 0;
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
     * Finds the first active room {@code userId} is currently present in, using the O(1) {@code voiceroom:user-room} index first
     * and falling back to a pipelined scan across {@code activeRoomIds} if the user has not yet been indexed.
     */
    public Optional<UUID> findActiveRoomForUser(Collection<UUID> activeRoomIds, UUID userId) {
        if (activeRoomIds.isEmpty()) {
            return Optional.empty();
        }
        String mapped = connection.sync().hget(USER_ROOM_KEY, userId.toString());
        if (mapped != null) {
            UUID indexedRoomId = UUID.fromString(mapped);
            if (activeRoomIds.contains(indexedRoomId) && isPresent(indexedRoomId, userId)) {
                return Optional.of(indexedRoomId);
            }
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

    public long count(UUID roomId) {
        Long n = connection.sync().zcount(key(roomId),
                Range.create((double) freshnessCutoff().toEpochMilli(), Double.POSITIVE_INFINITY));
        return n == null ? 0L : n;
    }

    public void clear(UUID roomId) {
        List<String> members = connection.sync().zrange(key(roomId), 0, -1);
        RedisAsyncCommands<String, String> redis = connection.async();
        RedisFuture<Long> deleted = redis.del(key(roomId), bannedKey(roomId));
        if (!members.isEmpty()) {
            await(deleted, redis.hdel(USER_ROOM_KEY, members.toArray(String[]::new)));
        } else {
            await(deleted);
        }
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
