package com.jtalk.livecommunication.domain.repository;

import com.jtalk.livecommunication.config.VoiceRoomProperties;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.inject.Singleton;

import java.util.Optional;
import java.util.UUID;

/** Active whisper sessions: {@code voiceroom:{id}:whisper:{speakerId}} -> targetId, with a safety TTL. */
@Singleton
public class WhisperRepository {

    private final StatefulRedisConnection<String, String> connection;
    private final long ttlSeconds;

    public WhisperRepository(StatefulRedisConnection<String, String> connection, VoiceRoomProperties properties) {
        this.connection = connection;
        this.ttlSeconds = properties.whisperTtl().toSeconds();
    }

    public void start(UUID roomId, String speakerId, String targetId) {
        connection.sync().set(key(roomId, speakerId), targetId, SetArgs.Builder.ex(ttlSeconds));
    }

    public Optional<String> find(UUID roomId, String speakerId) {
        return Optional.ofNullable(connection.sync().get(key(roomId, speakerId)));
    }

    public void stop(UUID roomId, String speakerId) {
        connection.sync().del(key(roomId, speakerId));
    }

    private static String key(UUID roomId, String speakerId) {
        return "voiceroom:{" + roomId + "}:whisper:" + speakerId;
    }
}
