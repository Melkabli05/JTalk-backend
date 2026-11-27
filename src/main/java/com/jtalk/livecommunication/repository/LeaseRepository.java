package com.jtalk.livecommunication.repository;

import com.jtalk.livecommunication.config.VoiceRoomProperties;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Optional;
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
    private static final Pattern HOST_LEASE_PATTERN = Pattern.compile("voiceroom:\\{([0-9a-fA-F-]{36})}:host-lease");
    private static final Pattern SOLO_PATTERN = Pattern.compile("voiceroom:\\{([0-9a-fA-F-]{36})}:solo");
    private static final Pattern IDLE_WARNING_PATTERN = Pattern.compile("voiceroom:\\{([0-9a-fA-F-]{36})}:idle-warning");

    private final long ttlSeconds;
    private final long hostLeaseTtlSeconds;
    private final long soloRoomTtlSeconds;
    private final long idleWarningTtlSeconds;

    public LeaseRepository(StatefulRedisConnection<String, String> connection, VoiceRoomProperties properties) {
        super(connection);
        this.ttlSeconds = properties.leaseTtl().toSeconds();
        this.hostLeaseTtlSeconds = properties.hostLeaseTtl().toSeconds();
        this.soloRoomTtlSeconds = properties.soloRoomTtl().toSeconds();
        this.idleWarningTtlSeconds = properties.idleWarningTtl().toSeconds();
    }

    /** Creates the lease unless one is already running (the first disconnect wins). */
    public boolean create(UUID roomId, UUID userId) {
        return "OK".equals(connection.sync().set(key(roomId, userId), "1", SetArgs.Builder.nx().ex(ttlSeconds)));
    }

    public boolean delete(UUID roomId, UUID userId) {
        return connection.sync().del(key(roomId, userId)) > 0;
    }

    public boolean createHostLease(UUID roomId) {
        return "OK".equals(connection.sync().set(hostLeaseKey(roomId), "1", SetArgs.Builder.nx().ex(hostLeaseTtlSeconds)));
    }

    public boolean deleteHostLease(UUID roomId) {
        return connection.sync().del(hostLeaseKey(roomId)) > 0;
    }

    public void refreshSoloTimer(UUID roomId) {
        connection.sync().setex(soloKey(roomId), soloRoomTtlSeconds, "1");
    }

    public boolean createIdleWarning(UUID roomId) {
        return "OK".equals(connection.sync().set(idleWarningKey(roomId), "1", SetArgs.Builder.nx().ex(idleWarningTtlSeconds)));
    }

    public void clearIdleWarning(UUID roomId) {
        connection.sync().del(idleWarningKey(roomId));
    }

    public void clearSoloAndIdleTimers(UUID roomId) {
        connection.sync().del(soloKey(roomId), idleWarningKey(roomId));
    }

    public void clearRoomLeases(UUID roomId) {
        connection.sync().del(hostLeaseKey(roomId), soloKey(roomId), idleWarningKey(roomId));
    }

    /** Parses the key of an expired Redis entry; empty if it is not a lease key. */
    public static Optional<LeaseKey> parseKey(String expiredKey) {
        Matcher matcher = KEY_PATTERN.matcher(expiredKey);
        return matcher.matches()
                ? Optional.of(new LeaseKey(UUID.fromString(matcher.group(1)), UUID.fromString(matcher.group(2))))
                : Optional.empty();
    }

    public static Optional<UUID> parseHostLeaseKey(String expiredKey) {
        return matchRoomId(HOST_LEASE_PATTERN, expiredKey);
    }

    public static Optional<UUID> parseSoloKey(String expiredKey) {
        return matchRoomId(SOLO_PATTERN, expiredKey);
    }

    public static Optional<UUID> parseIdleWarningKey(String expiredKey) {
        return matchRoomId(IDLE_WARNING_PATTERN, expiredKey);
    }

    private static Optional<UUID> matchRoomId(Pattern pattern, String expiredKey) {
        Matcher matcher = pattern.matcher(expiredKey);
        return matcher.matches() ? Optional.of(UUID.fromString(matcher.group(1))) : Optional.empty();
    }

    static String key(UUID roomId, UUID userId) {
        return roomKey(roomId, "lease:" + userId);
    }

    static String hostLeaseKey(UUID roomId) {
        return roomKey(roomId, "host-lease");
    }

    static String soloKey(UUID roomId) {
        return roomKey(roomId, "solo");
    }

    static String idleWarningKey(UUID roomId) {
        return roomKey(roomId, "idle-warning");
    }
}
