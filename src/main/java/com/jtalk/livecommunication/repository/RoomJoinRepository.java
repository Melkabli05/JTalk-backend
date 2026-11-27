package com.jtalk.livecommunication.repository;

import com.jtalk.livecommunication.config.VoiceRoomProperties;
import com.jtalk.livecommunication.domain.model.AudiencePage;
import com.jtalk.livecommunication.domain.model.ChatState;
import com.jtalk.livecommunication.domain.model.ParticipantPreview;
import com.jtalk.livecommunication.domain.model.RoomJoinSnapshot;
import com.jtalk.livecommunication.domain.model.RoomLiveStats;
import io.lettuce.core.KeyValue;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.io.ResourceResolver;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.jtalk.livecommunication.config.CommunicationConstants.MAX_STAGE_SEATS;

@Singleton
public class RoomJoinRepository extends RedisRepository {

    private static final int PAGE_SIZE = 20;
    private static final int CANDIDATE_COUNT = PAGE_SIZE + MAX_STAGE_SEATS + 1;
    private final PresenceRepository presence;
    private final CommentRepository comments;
    private final Clock clock;
    private final long stateTtlSeconds;
    private final LuaScript snapshotScript;

    public record RoomState(RoomLiveStats stats, RoomJoinSnapshot snapshot, @Nullable ParticipantPreview joiningProfile,
                            boolean onStage, boolean restoredSeat, boolean moderator) {
    }

    private record Snapshot(int entry, List<String> speakers, List<String> listeners, long participantCount,
                            long audienceCount, long handRaiseCount, List<UUID> handQueue, List<String> comments,
                            String topic, boolean muted, boolean moderator) {
        static Snapshot read(List<?> values) {
            return new Snapshot(((Long) values.getFirst()).intValue(),
                    strings(values.get(1)), strings(values.get(2)), (Long) values.get(3), (Long) values.get(4),
                    (Long) values.get(5), strings(values.get(6)).stream().map(UUID::fromString).toList(),
                    strings(values.get(7)), (String) values.get(8), (Long) values.get(9) == 1,
                    (Long) values.get(10) == 1);
        }
    }

    public RoomJoinRepository(StatefulRedisConnection<String, String> connection, PresenceRepository presence,
                              CommentRepository comments, Clock clock, VoiceRoomProperties properties, ResourceResolver resources) {
        super(connection);
        this.presence = presence;
        this.comments = comments;
        this.clock = clock;
        this.stateTtlSeconds = properties.stateTtl().toSeconds();
        this.snapshotScript = LuaScript.load(resources, "lua/room_join_snapshot.lua");
    }

    public Optional<RoomState> enter(UUID roomId, UUID userId, boolean host, int roomSeats) {
        return snapshot(roomId, userId, host, roomSeats, true);
    }

    public Optional<RoomState> read(UUID roomId, UUID userId, boolean host) {
        return snapshot(roomId, userId, host, 0, false);
    }

    private Optional<RoomState> snapshot(UUID roomId, UUID userId, boolean host, int roomSeats, boolean entering) {
        List<?> state = snapshotScript.execute(connection.sync(), ScriptOutputType.MULTI,
                new String[]{PresenceRepository.key(roomId), StageRepository.stageKey(roomId), StageRepository.queueKey(roomId),
                        CommentRepository.commentsKey(roomId), CommentRepository.topicKey(roomId), CommentRepository.mutedKey(roomId),
                        PresenceRepository.bannedKey(roomId), LeaseRepository.key(roomId, userId), StageRepository.moderatorsKey(roomId)},
                Long.toString(presence.freshnessCutoff().toEpochMilli()), Integer.toString(PAGE_SIZE), Integer.toString(MAX_STAGE_SEATS),
                userId.toString(), Boolean.toString(host), Integer.toString(CommentRepository.KEPT),
                Long.toString(clock.millis()), Long.toString(stateTtlSeconds), Integer.toString(roomSeats), Boolean.toString(entering));
        if ((Long) state.getFirst() == -1) {
            return Optional.empty();
        }
        Snapshot raw = Snapshot.read(state);
        List<String> speakerIds = raw.speakers();
        List<String> listeners = raw.listeners();
        List<String> visible = listeners.stream().limit(PAGE_SIZE).toList();
        var chatHistory = comments.readHistory(roomId, raw.comments());
        var redis = connection.async();
        var profiles = requestProfiles(redis, speakerIds, visible, entering ? userId.toString() : null);
        var leases = speakerIds.isEmpty() ? null
                : redis.mget(speakerIds.stream().map(id -> LeaseRepository.key(roomId, UUID.fromString(id))).toArray(String[]::new));
        List<UUID> authors = chatHistory.stream().map(comment -> comment.authorId()).distinct().toList();
        var muted = host && !authors.isEmpty()
                ? redis.smismember(CommentRepository.mutedKey(roomId), authors.stream().map(UUID::toString).toArray(String[]::new)) : null;
        var reactions = chatHistory.isEmpty() ? null : CommentRepository.requestReactions(redis, roomId, userId, chatHistory);

        Map<String, ParticipantPreview> previews = readProfiles(profiles);
        List<KeyValue<String, String>> leaseValues = leases == null ? List.of() : leases.toCompletableFuture().join();
        Set<String> reconnecting = new HashSet<>();
        for (int i = 0; i < leaseValues.size(); i++) {
            if (leaseValues.get(i).hasValue()) reconnecting.add(speakerIds.get(i));
        }
        List<UUID> mutedUsers = List.of();
        if (muted != null) {
            List<Boolean> flags = muted.toCompletableFuture().join();
            mutedUsers = java.util.stream.IntStream.range(0, flags.size()).filter(flags::get).mapToObj(authors::get).toList();
        }
        var chat = new ChatState(chatHistory, raw.topic(), raw.muted(), mutedUsers,
                CommentRepository.readReactions(chatHistory, reactions));
        var stats = new RoomLiveStats(speakerIds.stream().map(UUID::fromString).toList(), raw.participantCount(), raw.handRaiseCount());
        var speakers = speakerIds.stream().map(id -> {
            var preview = previews.get(id);
            return new ParticipantPreview(preview.userId(), preview.displayName(), preview.avatarUrl(), preview.nativeLanguage(), reconnecting.contains(id));
        }).toList();
        var snapshot = new RoomJoinSnapshot(speakers, raw.audienceCount(), page(listeners, previews), raw.handQueue(), chat);
        return Optional.of(new RoomState(stats, snapshot, entering ? previews.get(userId.toString()) : null, raw.entry() > 0, raw.entry() == 2, raw.moderator()));
    }

    private static List<String> strings(Object value) {
        return ((List<?>) value).stream().map(String.class::cast).toList();
    }

    public AudiencePage audience(UUID roomId, UUID cursor) {
        var redis = connection.async();
        var stage = speakers(redis, roomId);
        Long rank = cursor == null ? null : redis.zrevrank(PresenceRepository.key(roomId), cursor.toString()).toCompletableFuture().join();
        if (cursor != null && rank == null) {
            return new AudiencePage(List.of(), false, null);
        }
        var candidates = candidates(redis, roomId, recent(), rank == null ? 0 : rank + 1);
        await(stage, candidates);
        List<String> listeners = excludeSpeakers(candidates.toCompletableFuture().join(), stage.toCompletableFuture().join());
        return page(listeners, readProfiles(requestProfiles(redis, List.of(), listeners.stream().limit(PAGE_SIZE).toList(), null)));
    }

    private static AudiencePage page(List<String> listeners, Map<String, ParticipantPreview> profiles) {
        var items = listeners.stream().limit(PAGE_SIZE).map(profiles::get).toList();
        boolean hasMore = listeners.size() > PAGE_SIZE;
        return new AudiencePage(items, hasMore, hasMore ? items.getLast().userId() : null);
    }

    private Range<Double> recent() {
        return Range.create((double) presence.freshnessCutoff().toEpochMilli(), Double.POSITIVE_INFINITY);
    }

    private static RedisFuture<List<String>> speakers(RedisAsyncCommands<String, String> redis, UUID roomId) {
        return redis.zrange(StageRepository.stageKey(roomId), 0, MAX_STAGE_SEATS - 1);
    }

    private static RedisFuture<List<String>> candidates(RedisAsyncCommands<String, String> redis, UUID roomId,
                                                        Range<Double> recent, long offset) {
        return redis.zrevrangebyscore(PresenceRepository.key(roomId), recent, Limit.create(offset, CANDIDATE_COUNT));
    }

    private static List<String> excludeSpeakers(List<String> candidates, List<String> speakers) {
        var seated = new HashSet<>(speakers);
        return candidates.stream().filter(id -> !seated.contains(id)).toList();
    }

    private static Map<String, RedisFuture<List<KeyValue<String, String>>>> requestProfiles(
            RedisAsyncCommands<String, String> redis, List<String> speakers, List<String> listeners, String joiner) {
        var ids = new java.util.LinkedHashSet<>(speakers);
        ids.addAll(listeners);
        if (joiner != null) ids.add(joiner);
        Map<String, RedisFuture<List<KeyValue<String, String>>>> requests = new LinkedHashMap<>();
        for (String id : ids) {
            requests.put(id, redis.hmget("user:profile:{" + id + "}", "displayName", "avatarUrl", "nativeLanguage", "status"));
        }
        return requests;
    }

    private static Map<String, ParticipantPreview> readProfiles(Map<String, RedisFuture<List<KeyValue<String, String>>>> requests) {
        await(requests.values().toArray(RedisFuture<?>[]::new));
        Map<String, ParticipantPreview> result = new LinkedHashMap<>();
        requests.forEach((id, request) -> {
            var fields = request.toCompletableFuture().join();
            boolean active = "ACTIVE".equals(fields.get(3).getValueOrElse(null));
            result.put(id, new ParticipantPreview(UUID.fromString(id),
                    active ? fields.get(0).getValueOrElse(null) : null,
                    active ? fields.get(1).getValueOrElse(null) : null,
                    active ? fields.get(2).getValueOrElse(null) : null, false));
        });
        return result;
    }
}
