package com.jtalk.livecommunication.domain.repository;

import com.jtalk.livecommunication.config.VoiceRoomProperties;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.inject.Singleton;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grace leases for disconnected speakers: {@code voiceroom:{id}:lease:{userId}} with a TTL. When the key
 * expires Redis emits a keyspace event, which is what triggers the seat reclaim.
 */
@Singleton
public class LeaseRepository {

    /** Identifies whose lease an expired key belonged to. */
    public record LeaseKey(UUID roomId, String userId) {
    }

    private static final Pattern KEY_PATTERN = Pattern.compile("voiceroom:\\{([0-9a-fA-F-]{36})}:lease:(.+)");

    private final StatefulRedisConnection<String, String> connection;
    private final long ttlSeconds;

    public LeaseRepository(StatefulRedisConnection<String, String> connection, VoiceRoomProperties properties) {
        this.connection = connection;
        this.ttlSeconds = properties.leaseTtl().toSeconds();
    }

    /** Creates the lease unless one is already running (the first disconnect wins). */
    public boolean create(UUID roomId, String userId) {
        return "OK".equals(connection.sync().set(key(roomId, userId), "1", SetArgs.Builder.nx().ex(ttlSeconds)));
    }

    public boolean delete(UUID roomId, String userId) {
        return connection.sync().del(key(roomId, userId)) > 0;
    }

    public boolean exists(UUID roomId, String userId) {
        return connection.sync().exists(key(roomId, userId)) > 0;
    }

    /** Parses the key of an expired Redis entry; empty if it is not a lease key. */
    public static Optional<LeaseKey> parseKey(String expiredKey) {
        Matcher matcher = KEY_PATTERN.matcher(expiredKey);
        return matcher.matches()
                ? Optional.of(new LeaseKey(UUID.fromString(matcher.group(1)), matcher.group(2)))
                : Optional.empty();
    }

    private static String key(UUID roomId, String userId) {
        return "voiceroom:{" + roomId + "}:lease:" + userId;
    }
}
