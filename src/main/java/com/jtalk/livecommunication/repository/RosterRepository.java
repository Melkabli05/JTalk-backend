package com.jtalk.livecommunication.repository;

import com.jtalk.livecommunication.config.VoiceRoomProperties;
import io.lettuce.core.KeyValue;
import io.lettuce.core.Range;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.io.ResourceResolver;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Who is in a room right now, in the two round trips it takes: the stage, the people present and (stale entries trimmed first) go out
 * together, then one {@code MGET} for the grace leases of exactly those people. Read one by one these were five round trips. The keys
 * of one room share a hash slot, so the {@code MGET} is valid on a Redis cluster.
 */
@Singleton
public class RosterRepository extends RedisRepository {

    /**
     * @param speakers    who is seated on the stage
     * @param present     who is in the room: confirmed by the media server, or joined recently
     * @param reconnecting the present people whose media dropped and whose seat is on a grace lease
     */
    public record Roster(List<UUID> speakers, List<UUID> present, Set<UUID> reconnecting, Set<UUID> moderators) {
        public Roster(List<UUID> speakers, List<UUID> present, Set<UUID> reconnecting) {
            this(speakers, present, reconnecting, Set.of());
        }
    }

    /** What leaving a room found: whether the person was listed as present, and whether they hold a stage seat. */
    public record Departure(boolean wasPresent, boolean onStage) {
    }

    public enum HostExitAction {
        CLOSE, CLOSE_HOST_ABSENT, NEED_SUCCESSOR, SUCCESSOR_NOT_IN_ROOM, TRANSFER
    }

    public record HostExitOutcome(HostExitAction action, @Nullable UUID nextHostId, boolean nextHostWasOnStage) {
    }

    private final PresenceRepository presence;
    private final Clock clock;
    private final String ttlSeconds;
    private final LuaScript hostExitScript;

    public RosterRepository(StatefulRedisConnection<String, String> connection, PresenceRepository presence,
                            Clock clock, VoiceRoomProperties properties, ResourceResolver resources) {
        super(connection);
        this.presence = presence;
        this.clock = clock;
        this.ttlSeconds = Long.toString(properties.stateTtl().toSeconds());
        this.hostExitScript = LuaScript.load(resources, "lua/host_exit.lua");
    }

    public HostExitOutcome exitHost(UUID roomId, UUID hostId, @Nullable UUID successorUserId, boolean abruptExpiry) {
        List<?> res = hostExitScript.execute(connection.sync(), ScriptOutputType.MULTI,
                new String[]{PresenceRepository.key(roomId), StageRepository.stageKey(roomId),
                        StageRepository.queueKey(roomId), StageRepository.moderatorsKey(roomId)},
                hostId.toString(), successorUserId == null ? "" : successorUserId.toString(),
                Long.toString(presence.freshnessCutoff().toEpochMilli()), Long.toString(clock.millis()),
                ttlSeconds, abruptExpiry ? "1" : "0");
        HostExitAction action = HostExitAction.valueOf((String) res.getFirst());
        if (action == HostExitAction.TRANSFER) {
            connection.sync().hdel(PresenceRepository.USER_ROOM_KEY, hostId.toString());
            return new HostExitOutcome(action, UUID.fromString((String) res.get(1)), "1".equals(res.get(2)));
        }
        return new HostExitOutcome(action, null, false);
    }

    public Roster read(UUID roomId) {
        RedisAsyncCommands<String, String> redis = connection.async();
        RedisFuture<List<String>> speakers = redis.zrange(StageRepository.stageKey(roomId), 0, -1);
        // Commands on one connection run in order, so the trim is done before the read that follows it.
        RedisFuture<Long> trimmed = redis.zremrangebyscore(PresenceRepository.key(roomId),
                Range.create(Double.NEGATIVE_INFINITY, (double) presence.freshnessCutoff().toEpochMilli() - 1));
        RedisFuture<List<String>> present = redis.zrange(PresenceRepository.key(roomId), 0, -1);
        RedisFuture<Set<String>> moderators = redis.smembers(StageRepository.moderatorsKey(roomId));
        await(speakers, trimmed, present, moderators);

        List<UUID> people = present.toCompletableFuture().join().stream().map(UUID::fromString).toList();
        Set<UUID> modIds = moderators.toCompletableFuture().join().stream().map(UUID::fromString)
                .collect(java.util.stream.Collectors.toSet());
        return new Roster(speakers.toCompletableFuture().join().stream().map(UUID::fromString).toList(), people,
                leaseHolders(roomId, people), modIds);
    }

    /** Drops the person from the room and its hand-raise queue, and looks up their seat: three commands, one round trip. */
    public Departure leave(UUID roomId, UUID userId) {
        RedisAsyncCommands<String, String> redis = connection.async();
        String id = userId.toString();
        RedisFuture<Long> present = redis.zrem(PresenceRepository.key(roomId), id);
        RedisFuture<Long> userIndex = redis.hdel(PresenceRepository.USER_ROOM_KEY, id);
        RedisFuture<Long> queued = redis.zrem(StageRepository.queueKey(roomId), id);
        RedisFuture<Long> mod = redis.srem(StageRepository.moderatorsKey(roomId), id);
        RedisFuture<Double> seat = redis.zscore(StageRepository.stageKey(roomId), id);
        await(present, userIndex, queued, mod, seat);
        return new Departure(present.toCompletableFuture().join() > 0, seat.toCompletableFuture().join() != null);
    }

    /** Which of the users currently hold a lease: one MGET for all of them. */
    private Set<UUID> leaseHolders(UUID roomId, List<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        List<KeyValue<String, String>> leases = connection.sync()
                .mget(userIds.stream().map(id -> LeaseRepository.key(roomId, id)).toArray(String[]::new));
        Set<UUID> holders = new HashSet<>();
        for (int i = 0; i < userIds.size(); i++) {
            if (leases.get(i).hasValue()) {
                holders.add(userIds.get(i));
            }
        }
        return holders;
    }
}
