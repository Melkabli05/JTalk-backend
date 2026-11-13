package com.jtalk.livecommunication.domain.repository;

import com.jtalk.livecommunication.config.VoiceRoomProperties;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.inject.Singleton;

import java.util.Optional;
import java.util.UUID;

/** Active whisper sessions: {@code voiceroom:{id}:whisper:{speakerId}} -> targetId, with a safety TTL. */
@Singleton
public class WhisperRepository extends RedisRepository {

    private final long ttlSeconds;

    public WhisperRepository(StatefulRedisConnection<String, String> connection, VoiceRoomProperties properties) {
        super(connection);
        this.ttlSeconds = properties.whisperTtl().toSeconds();
    }

    public void start(UUID roomId, UUID speakerId, UUID targetId) {
        connection.sync().set(key(roomId, speakerId), targetId.toString(), SetArgs.Builder.ex(ttlSeconds));
    }

    public Optional<UUID> find(UUID roomId, UUID speakerId) {
        return Optional.ofNullable(connection.sync().get(key(roomId, speakerId))).map(UUID::fromString);
    }

    public void stop(UUID roomId, UUID speakerId) {
        connection.sync().del(key(roomId, speakerId));
    }

    private static String key(UUID roomId, UUID speakerId) {
        return roomKey(roomId, "whisper:" + speakerId);
    }
}
