package com.jtalk.livecommunication.domain.repository;

import com.jtalk.livecommunication.config.VoiceRoomProperties;
import com.jtalk.livecommunication.domain.enums.SeatResult;
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
 * Stage state in Redis. Keys share the {@code {roomId}} hash tag so multi-key Lua scripts stay valid
 * on Redis Cluster.
 * <ul>
 *   <li>{@code voiceroom:{id}:stage} - ZSET userId -> seated-at millis</li>
 *   <li>{@code voiceroom:{id}:hand-queue} - ZSET userId -> raised-at millis (FIFO)</li>
 * </ul>
 */
@Singleton
public class StageRepository {

    private final StatefulRedisConnection<String, String> connection;
    private final Clock clock;
    private final String ttlSeconds;
    private final LuaScript raiseHandScript;
    private final LuaScript approveScript;
    private final LuaScript promoteNextScript;

    public StageRepository(StatefulRedisConnection<String, String> connection,
                           Clock clock,
                           VoiceRoomProperties properties,
                           ResourceResolver resources) {
        this.connection = connection;
        this.clock = clock;
        this.ttlSeconds = Long.toString(properties.stateTtl().toSeconds());
        this.raiseHandScript = LuaScript.load(resources, "lua/raise_hand.lua");
        this.approveScript = LuaScript.load(resources, "lua/approve_speaker.lua");
        this.promoteNextScript = LuaScript.load(resources, "lua/promote_next.lua");
    }

    public int raiseHand(UUID roomId, String userId) {
        Long position = raiseHandScript.execute(redis(), ScriptOutputType.INTEGER,
                new String[]{stageKey(roomId), queueKey(roomId)},
                userId, nowMillis(), ttlSeconds);
        return position.intValue();
    }

    public void lowerHand(UUID roomId, String userId) {
        redis().zrem(queueKey(roomId), userId);
    }

    public SeatResult approve(UUID roomId, String userId, int maxSeats) {
        Long code = approveScript.execute(redis(), ScriptOutputType.INTEGER,
                new String[]{stageKey(roomId), queueKey(roomId)},
                userId, Integer.toString(maxSeats), nowMillis(), ttlSeconds);
        return switch (code.intValue()) {
            case 1 -> SeatResult.SEATED;
            case 0 -> SeatResult.STAGE_FULL;
            case 2 -> SeatResult.ALREADY_ON_STAGE;
            case 3 -> SeatResult.NOT_IN_QUEUE;
            default -> throw new IllegalStateException("Unexpected approve_speaker.lua result: " + code);
        };
    }

    /** Seats the longest-waiting hand-raiser if a seat is free; returns who was seated. */
    public Optional<String> promoteNext(UUID roomId, int maxSeats) {
        String userId = promoteNextScript.execute(redis(), ScriptOutputType.VALUE,
                new String[]{stageKey(roomId), queueKey(roomId)},
                Integer.toString(maxSeats), nowMillis(), ttlSeconds);
        return Optional.ofNullable(userId);
    }

    public boolean leaveStage(UUID roomId, String userId) {
        return redis().zrem(stageKey(roomId), userId) > 0;
    }

    public boolean isOnStage(UUID roomId, String userId) {
        return redis().zscore(stageKey(roomId), userId) != null;
    }

    public StageState snapshot(UUID roomId) {
        RedisCommands<String, String> redis = redis();
        return new StageState(
                redis.zrange(stageKey(roomId), 0, -1),
                redis.zrange(queueKey(roomId), 0, -1));
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

    private static String stageKey(UUID roomId) {
        return "voiceroom:{" + roomId + "}:stage";
    }

    private static String queueKey(UUID roomId) {
        return "voiceroom:{" + roomId + "}:hand-queue";
    }
}
