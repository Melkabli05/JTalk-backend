package com.jtalk.livecommunication.domain.repository;

import com.jtalk.livecommunication.domain.model.RoomLiveStats;
import io.lettuce.core.LettuceFutures;
import io.lettuce.core.Range;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Live stage / queue / presence numbers for many rooms at once (the discovery feed). All commands are issued
 * asynchronously on the shared connection and awaited together, so the whole batch costs one network round trip.
 * Auto-flush is deliberately left on: switching it off would stall every other request sharing the connection.
 */
@Singleton
@Slf4j
public class LiveStatsRepository extends RedisRepository {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private record Pending(RedisFuture<List<String>> speakers, RedisFuture<Long> handRaises, RedisFuture<Long> present) {
    }

    private final PresenceRepository presence;

    public LiveStatsRepository(StatefulRedisConnection<String, String> connection, PresenceRepository presence) {
        super(connection);
        this.presence = presence;
    }

    /** Returns an entry for every requested room; a room whose numbers could not be read gets {@link RoomLiveStats#EMPTY}. */
    public Map<UUID, RoomLiveStats> find(Collection<UUID> roomIds) {
        if (roomIds.isEmpty()) {
            return Map.of();
        }
        RedisAsyncCommands<String, String> redis = connection.async();
        Range<Double> recent = Range.create((double) presence.freshnessCutoff().toEpochMilli(), Double.POSITIVE_INFINITY);

        Map<UUID, Pending> pending = new LinkedHashMap<>();
        for (UUID roomId : roomIds) {
            pending.put(roomId, new Pending(
                    redis.zrange(StageRepository.stageKey(roomId), 0, -1),
                    redis.zcard(StageRepository.queueKey(roomId)),
                    redis.zcount(PresenceRepository.key(roomId), recent)));
        }
        LettuceFutures.awaitAll(TIMEOUT, pending.values().stream()
                .flatMap(p -> java.util.stream.Stream.of(p.speakers, p.handRaises, p.present))
                .toArray(RedisFuture[]::new));

        Map<UUID, RoomLiveStats> stats = new LinkedHashMap<>();
        pending.forEach((roomId, p) -> stats.put(roomId, read(roomId, p)));
        return stats;
    }

    private static RoomLiveStats read(UUID roomId, Pending p) {
        try {
            return new RoomLiveStats(
                    p.speakers.get(0, TimeUnit.MILLISECONDS).stream().map(UUID::fromString).toList(),
                    p.present.get(0, TimeUnit.MILLISECONDS),
                    p.handRaises.get(0, TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            log.warn("Live stats for room {} unavailable", roomId, e);
            return RoomLiveStats.EMPTY;
        }
    }
}
