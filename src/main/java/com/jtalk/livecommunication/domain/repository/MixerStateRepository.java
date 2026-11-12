package com.jtalk.livecommunication.domain.repository;

import com.jtalk.livecommunication.config.VoiceRoomProperties;
import com.jtalk.livecommunication.domain.enums.MixerMode;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.inject.Singleton;

import java.util.UUID;

/** Which mixes are running for a room, so START/STOP assignments are only issued on a real change. */
@Singleton
public class MixerStateRepository {

    private final StatefulRedisConnection<String, String> connection;
    private final long ttlSeconds;

    public MixerStateRepository(StatefulRedisConnection<String, String> connection, VoiceRoomProperties properties) {
        this.connection = connection;
        this.ttlSeconds = properties.stateTtl().toSeconds();
    }

    /** Returns true if the mix was not running before. */
    public boolean markStarted(UUID roomId, MixerMode mode) {
        return "OK".equals(connection.sync().set(key(roomId, mode), "1", SetArgs.Builder.nx().ex(ttlSeconds)));
    }

    /** Returns true if the mix was running before. */
    public boolean markStopped(UUID roomId, MixerMode mode) {
        return connection.sync().del(key(roomId, mode)) > 0;
    }

    private static String key(UUID roomId, MixerMode mode) {
        return "voiceroom:{" + roomId + "}:mixer:" + mode;
    }
}
