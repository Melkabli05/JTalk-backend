package com.jtalk.livecommunication.repository;

import com.jtalk.livecommunication.config.VoiceRoomProperties;
import io.lettuce.core.KeyValue;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.inject.Singleton;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grace leases for disconnected speakers: {@code voiceroom:{id}:lease:{userId}} with a TTL. When the key expires Redis
 * emits a keyspace event, which is what triggers the seat reclaim.
 */
@Singleton
public class LeaseRepository extends RedisRepository {

    /** Identifies whose lease an expired key belonged to. */
    public record LeaseKey(UUID roomId, UUID userId) {
    }

    private static final Pattern KEY_PATTERN = Pattern.compile("voiceroom:\\{([0-9a-fA-F-]{36})}:lease:([0-9a-fA-F-]{36})");

    private final long ttlSeconds;

    public LeaseRepository(StatefulRedisConnection<String, String> connection, VoiceRoomProperties properties) {
        super(connection);
        this.ttlSeconds = properties.leaseTtl().toSeconds();
    }

    /** Creates the lease unless one is already running (the first disconnect wins). */
    public boolean create(UUID roomId, UUID userId) {
        return "OK".equals(connection.sync().set(key(roomId, userId), "1", SetArgs.Builder.nx().ex(ttlSeconds)));
    }

    public boolean delete(UUID roomId, UUID userId) {
        return connection.sync().del(key(roomId, userId)) > 0;
    }

    /** Which of the users currently hold a lease: one MGET for all of them (their keys share a hash slot). */
    public Set<UUID> usersWithLease(UUID roomId, List<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        List<KeyValue<String, String>> leases = connection.sync().mget(userIds.stream().map(id -> key(roomId, id)).toArray(String[]::new));
        Set<UUID> holders = new HashSet<>();
        for (int i = 0; i < userIds.size(); i++) {
            if (leases.get(i).hasValue()) {
                holders.add(userIds.get(i));
            }
        }
        return holders;
    }

    /** Parses the key of an expired Redis entry; empty if it is not a lease key. */
    public static Optional<LeaseKey> parseKey(String expiredKey) {
        Matcher matcher = KEY_PATTERN.matcher(expiredKey);
        return matcher.matches()
                ? Optional.of(new LeaseKey(UUID.fromString(matcher.group(1)), UUID.fromString(matcher.group(2))))
                : Optional.empty();
    }

    private static String key(UUID roomId, UUID userId) {
        return roomKey(roomId, "lease:" + userId);
    }
}
