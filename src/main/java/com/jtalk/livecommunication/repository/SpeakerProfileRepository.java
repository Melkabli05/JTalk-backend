package com.jtalk.livecommunication.repository;

import com.jtalk.livecommunication.domain.model.RoomSpeakerSummary;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import jakarta.inject.Singleton;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Batch-reads cached user profile hashes written by user-service in a single Redis round trip. */
@Singleton
public class SpeakerProfileRepository extends RedisRepository {

    public SpeakerProfileRepository(StatefulRedisConnection<String, String> connection) {
        super(connection);
    }

    public Map<UUID, RoomSpeakerSummary> findSummaries(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        LinkedHashSet<UUID> uniqueIds = new LinkedHashSet<>(userIds);
        RedisAsyncCommands<String, String> redis = connection.async();
        Map<UUID, RedisFuture<List<KeyValue<String, String>>>> requests = new LinkedHashMap<>();
        for (UUID id : uniqueIds) {
            requests.put(id, redis.hmget("user:profile:{" + id + "}",
                    "username", "displayName", "avatarUrl", "countryCode", "nativeLanguage", "status"));
        }
        await(requests.values().toArray(RedisFuture<?>[]::new));

        Map<UUID, RoomSpeakerSummary> result = new LinkedHashMap<>();
        requests.forEach((id, future) -> {
            List<KeyValue<String, String>> fields = future.toCompletableFuture().join();
            boolean active = "ACTIVE".equals(fields.get(5).getValueOrElse(null));
            if (active) {
                result.put(id, new RoomSpeakerSummary(
                        id,
                        fields.get(0).getValueOrElse(null),
                        fields.get(1).getValueOrElse(null),
                        fields.get(2).getValueOrElse(null),
                        fields.get(3).getValueOrElse(null),
                        fields.get(4).getValueOrElse(null)));
            }
        });
        return result;
    }
}
