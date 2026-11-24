package com.jtalk.livecommunication.repository;

import com.jtalk.livecommunication.domain.model.RoomLiveStats;
import io.lettuce.core.Range;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static com.jtalk.livecommunication.config.CommunicationConstants.MAX_STAGE_SEATS;

/**
 * Live stage / queue / presence numbers for many rooms at once (the discovery feed). All commands are issued
 * asynchronously on the shared connection and awaited together, so the whole batch costs one network round trip.
 * Auto-flush is deliberately left on: switching it off would stall every other request sharing the connection.
 */
@Singleton
@Slf4j
public class LiveStatsRepository extends RedisRepository {
    private static final String COUNT_PRESENCE = """
            local total = redis.call('ZCOUNT', KEYS[1], ARGV[1], '+inf')
            local active = 0
            for _, user in ipairs(redis.call('ZRANGE', KEYS[2], 0, tonumber(ARGV[2]) - 1)) do
                local score = redis.call('ZSCORE', KEYS[1], user)
                if score and tonumber(score) >= tonumber(ARGV[1]) then active = active + 1 end
            end
            return {total, total - active}
            """;

    public record AudienceStats(RoomLiveStats room, long audienceCount) {
    }

    private record Pending(RedisFuture<List<String>> speakers, RedisFuture<Long> handRaises, RedisFuture<Long> present) {
    }

    public record SessionState(RoomLiveStats stats, boolean chatMuted) {
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
        Range<Double> recent = recentPresence();

        Map<UUID, Pending> pending = new LinkedHashMap<>();
        for (UUID roomId : roomIds) {
            pending.put(roomId, requestStats(redis, roomId, recent));
        }
        await(pending.values().stream()
                .flatMap(p -> java.util.stream.Stream.of(p.speakers, p.handRaises, p.present))
                .toArray(RedisFuture[]::new));

        Map<UUID, RoomLiveStats> stats = new LinkedHashMap<>();
        pending.forEach((roomId, p) -> stats.put(roomId, read(roomId, p)));
        return stats;
    }

    public SessionState findForSession(UUID roomId, UUID userId) {
        RedisAsyncCommands<String, String> redis = connection.async();
        Pending pending = requestStats(redis, roomId, recentPresence());
        RedisFuture<Boolean> muted = redis.sismember(CommentRepository.mutedKey(roomId), userId.toString());
        await(pending.speakers(), pending.handRaises(), pending.present(), muted);
        return new SessionState(read(roomId, pending), muted.toCompletableFuture().join());
    }

    public Map<UUID, AudienceStats> findWithAudience(Collection<UUID> rooms) {
        record Counts(RedisFuture<List<String>> speakers, RedisFuture<Long> raised, RedisFuture<List<Long>> presence) {}
        var redis = connection.async();
        long cutoff = presence.freshnessCutoff().toEpochMilli();
        Map<UUID, Counts> pending = new LinkedHashMap<>();
        rooms.forEach(room -> pending.put(room, new Counts(
                redis.zrange(StageRepository.stageKey(room), 0, -1),
                redis.zcard(StageRepository.queueKey(room)),
                countPresence(redis, room, cutoff))));
        await(pending.values().stream().flatMap(count -> java.util.stream.Stream.of(count.speakers(), count.raised(), count.presence()))
                .toArray(RedisFuture<?>[]::new));
        Map<UUID, AudienceStats> results = new LinkedHashMap<>();
        pending.forEach((room, count) -> {
            List<Long> totals = count.presence().toCompletableFuture().join();
            results.put(room, new AudienceStats(new RoomLiveStats(
                    count.speakers().toCompletableFuture().join().stream().map(UUID::fromString).toList(),
                    totals.getFirst(), count.raised().toCompletableFuture().join()), totals.getLast()));
        });
        return results;
    }

    static RedisFuture<List<Long>> countPresence(RedisAsyncCommands<String, String> redis, UUID room, long cutoff) {
        return redis.eval(COUNT_PRESENCE, ScriptOutputType.MULTI,
                new String[]{PresenceRepository.key(room), StageRepository.stageKey(room)},
                Long.toString(cutoff), Integer.toString(MAX_STAGE_SEATS));
    }

    private Range<Double> recentPresence() {
        return Range.create((double) presence.freshnessCutoff().toEpochMilli(), Double.POSITIVE_INFINITY);
    }

    private static Pending requestStats(RedisAsyncCommands<String, String> redis, UUID roomId, Range<Double> recent) {
        return new Pending(redis.zrange(StageRepository.stageKey(roomId), 0, -1),
                redis.zcard(StageRepository.queueKey(roomId)), redis.zcount(PresenceRepository.key(roomId), recent));
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
