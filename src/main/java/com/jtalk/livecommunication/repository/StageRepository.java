package com.jtalk.livecommunication.repository;

import com.jtalk.livecommunication.config.VoiceRoomProperties;
import com.jtalk.livecommunication.domain.model.StageState;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.core.io.ResourceResolver;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Stage state in Redis. Keys share the {@code {roomId}} hash tag so multi-key Lua scripts stay valid on Redis Cluster.
 * <ul>
 *   <li>{@code voiceroom:{id}:stage} - ZSET userId -> seated-at millis (the host is seated at creation)</li>
 *   <li>{@code voiceroom:{id}:hand-queue} - ZSET userId -> raised-at millis (FIFO)</li>
 * </ul>
 */
@Singleton
public class StageRepository extends RedisRepository {

    private final Clock clock;
    private final String ttlSeconds;
    private final LuaScript raiseHandScript;
    private final LuaScript claimSeatScript;
    private final LuaScript releaseAndPromoteScript;

    public StageRepository(StatefulRedisConnection<String, String> connection,
                           Clock clock,
                           VoiceRoomProperties properties,
                           ResourceResolver resources) {
        super(connection);
        this.clock = clock;
        this.ttlSeconds = Long.toString(properties.stateTtl().toSeconds());
        this.raiseHandScript = LuaScript.load(resources, "lua/raise_hand.lua");
        this.claimSeatScript = LuaScript.load(resources, "lua/claim_stage_seat.lua");
        this.releaseAndPromoteScript = LuaScript.load(resources, "lua/release_and_promote.lua");
    }

    public void initializeStage(UUID roomId, UUID hostId) {
        RedisCommands<String, String> redis = redis();
        redis.zadd(stageKey(roomId), clock.millis(), hostId.toString());
        redis.expire(stageKey(roomId), Long.parseLong(ttlSeconds));
    }

    /** Adds the user to the queue if not already queued. Returns the new 1-based position, 0 if already queued, or -1 if already on stage. */
    public int raiseHand(UUID roomId, UUID userId) {
        Long position = raiseHandScript.execute(redis(), ScriptOutputType.INTEGER,
                new String[]{stageKey(roomId), queueKey(roomId)},
                userId.toString(), nowMillis(), ttlSeconds);
        return position.intValue();
    }

    public boolean removeHandRaise(UUID roomId, UUID userId) {
        return redis().zrem(queueKey(roomId), userId.toString()) > 0;
    }

    /** Atomically takes a free seat (and leaves the queue). Returns false if the stage is full. */
    public boolean tryClaimStageSeat(UUID roomId, UUID userId, int maxSeats) {
        Long result = claimSeatScript.execute(redis(), ScriptOutputType.INTEGER,
                new String[]{stageKey(roomId), queueKey(roomId)},
                userId.toString(), Integer.toString(maxSeats), nowMillis(), ttlSeconds);
        return result != 0;
    }

    /** Frees the user's seat and, atomically, seats the longest-waiting hand-raiser; returns who was seated. */
    public Optional<UUID> releaseAndPromoteNext(UUID roomId, UUID userId) {
        String promoted = releaseAndPromoteScript.execute(redis(), ScriptOutputType.VALUE,
                new String[]{stageKey(roomId), queueKey(roomId)},
                userId.toString(), nowMillis(), ttlSeconds);
        return Optional.ofNullable(promoted).map(UUID::fromString);
    }

    public boolean isOnStage(UUID roomId, UUID userId) {
        return redis().zscore(stageKey(roomId), userId.toString()) != null;
    }

    public StageState snapshot(UUID roomId) {
        RedisCommands<String, String> redis = redis();
        return new StageState(
                redis.zrange(stageKey(roomId), 0, -1).stream().map(UUID::fromString).toList(),
                redis.zrange(queueKey(roomId), 0, -1).stream().map(UUID::fromString).toList());
    }

    public void clear(UUID roomId) {
        redis().del(stageKey(roomId), queueKey(roomId));
    }

    private RedisCommands<String, String> redis() {
        return connection.sync();
    }

    private String nowMillis() {
        return Long.toString(clock.millis());
    }

    static String stageKey(UUID roomId) {
        return roomKey(roomId, "stage");
    }

    static String queueKey(UUID roomId) {
        return roomKey(roomId, "hand-queue");
    }
}
