package com.jtalk.livecommunication.repository;

import io.lettuce.core.KeyValue;
import io.lettuce.core.Range;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import jakarta.inject.Singleton;

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
    public record Roster(List<UUID> speakers, List<UUID> present, Set<UUID> reconnecting) {
    }

    private final PresenceRepository presence;

    public RosterRepository(StatefulRedisConnection<String, String> connection, PresenceRepository presence) {
        super(connection);
        this.presence = presence;
    }

    public Roster read(UUID roomId) {
        RedisAsyncCommands<String, String> redis = connection.async();
        RedisFuture<List<String>> speakers = redis.zrange(StageRepository.stageKey(roomId), 0, -1);
        // Commands on one connection run in order, so the trim is done before the read that follows it.
        RedisFuture<Long> trimmed = redis.zremrangebyscore(PresenceRepository.key(roomId),
                Range.create(Double.NEGATIVE_INFINITY, (double) presence.freshnessCutoff().toEpochMilli() - 1));
        RedisFuture<List<String>> present = redis.zrange(PresenceRepository.key(roomId), 0, -1);
        await(speakers, trimmed, present);

        List<UUID> people = present.toCompletableFuture().join().stream().map(UUID::fromString).toList();
        return new Roster(speakers.toCompletableFuture().join().stream().map(UUID::fromString).toList(), people,
                leaseHolders(roomId, people));
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
