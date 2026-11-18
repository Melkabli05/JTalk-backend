package com.jtalk.livecommunication.repository;

import com.jtalk.livecommunication.config.VoiceRoomProperties;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.ZAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.micronaut.core.io.ResourceResolver;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Who is in the room right now: ZSET {@code voiceroom:{id}:presence}, userId -> when they joined (epoch millis) until LiveKit
 * confirms their media connection, then {@link #CONNECTED}, which no staleness check ever trims. Someone who joined but never
 * connected ages out on read, so no sweeper is needed. Kicked users are kept in {@code voiceroom:{id}:banned}.
 */
@Singleton
public class PresenceRepository extends RedisRepository {

    private static final double CONNECTED = Long.MAX_VALUE;

    /** What joining a room came to. */
    public enum Entry {
        /** The host removed this user from the room: nothing was changed. */
        REMOVED,
        /** In the room, as a listener. */
        LISTENER,
        /** In the room, holding a stage seat. */
        ON_STAGE,
        /** In the room, holding a stage seat they had lost the connection to: they are back within the grace lease. */
        ON_STAGE_AGAIN
    }

    private final Clock clock;
    private final Duration presenceTimeout;
    private final long ttlSeconds;
    private final LuaScript joinScript;

    public PresenceRepository(StatefulRedisConnection<String, String> connection, Clock clock, VoiceRoomProperties properties,
                              ResourceResolver resources) {
        super(connection);
        this.clock = clock;
        this.presenceTimeout = properties.presenceTimeout();
        this.ttlSeconds = properties.stateTtl().toSeconds();
        this.joinScript = LuaScript.load(resources, "lua/join_room.lua");
    }

    /**
     * Joins the room in one round trip: refuses a user the host removed, marks the rest present, and says whether they hold a seat (and
     * whether a grace lease on it was released). Done in a script because the answer to each step decides the next.
     */
    public Entry enter(UUID roomId, UUID userId) {
        Long result = joinScript.execute(connection.sync(), ScriptOutputType.INTEGER,
                new String[]{bannedKey(roomId), StageRepository.stageKey(roomId), key(roomId), LeaseRepository.key(roomId, userId)},
                userId.toString(), Long.toString(clock.millis()), Long.toString(ttlSeconds));
        return switch (result.intValue()) {
            case -1 -> Entry.REMOVED;
            case 0 -> Entry.LISTENER;
            case 1 -> Entry.ON_STAGE;
            default -> Entry.ON_STAGE_AGAIN;
        };
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

    private static String bannedKey(UUID roomId) {
        return roomKey(roomId, "banned");
    }

    static String key(UUID roomId) {
        return roomKey(roomId, "presence");
    }
}
