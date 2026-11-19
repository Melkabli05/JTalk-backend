package com.jtalk.livecommunication.repository;

import com.jtalk.livecommunication.config.VoiceRoomProperties;
import com.jtalk.livecommunication.domain.model.ChatState;
import com.jtalk.livecommunication.domain.model.RoomComment;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A room's chat, kept in Redis and nowhere else: chat is gone with the room, so it costs the database nothing.
 * <ul>
 *   <li>{@code voiceroom:{id}:comments} - LIST of comments as JSON, newest first, cut to the last {@link #KEPT}</li>
 *   <li>{@code voiceroom:{id}:topic} - the pinned topic</li>
 *   <li>{@code voiceroom:{id}:chat-muted} - SET of the people the host muted</li>
 *   <li>{@code voiceroom:{id}:chat-rate:{userId}} - how many comments a person made in the current window</li>
 * </ul>
 */
@Singleton
@Slf4j
public class CommentRepository extends RedisRepository {

    /** What posting came to. */
    public enum Posting {
        KEPT, NOT_IN_ROOM, MUTED, TOO_FAST
    }

    static final int KEPT = 50;

    private final PresenceRepository presence;
    private final ObjectMapper json;
    private final LuaScript postScript;
    private final long ttlSeconds;
    private final int perWindow;
    private final long windowSeconds;

    public CommentRepository(StatefulRedisConnection<String, String> connection, PresenceRepository presence, ObjectMapper json,
                             ResourceResolver resources, VoiceRoomProperties properties) {
        super(connection);
        this.presence = presence;
        this.json = json;
        this.postScript = LuaScript.load(resources, "lua/post_comment.lua");
        this.ttlSeconds = properties.stateTtl().toSeconds();
        this.perWindow = properties.commentsPerWindow();
        this.windowSeconds = Math.max(1, properties.commentWindow().toSeconds());
    }

    /**
     * Keeps the comment unless the person is not in the room, is muted or has used their share of the window: one round trip, decided
     * atomically, so a comment costs Redis a single script call however many rules it passes.
     */
    public Posting post(UUID roomId, UUID userId, RoomComment comment) {
        Long result = postScript.execute(connection.sync(), ScriptOutputType.INTEGER,
                new String[]{mutedKey(roomId), rateKey(roomId, userId), commentsKey(roomId), PresenceRepository.key(roomId)},
                userId.toString(), write(comment), Integer.toString(perWindow), Long.toString(windowSeconds),
                Integer.toString(KEPT), Long.toString(ttlSeconds), Long.toString(presence.freshnessCutoff().toEpochMilli()));
        return switch (result.intValue()) {
            case -3 -> Posting.NOT_IN_ROOM;
            case -1 -> Posting.MUTED;
            case -2 -> Posting.TOO_FAST;
            default -> Posting.KEPT;
        };
    }

    /**
     * What a person in the room sees on opening the chat: comments (oldest first), the topic and whether they are muted, in one round trip,
     * with the check that they are in the room travelling in it. The host also gets everyone muted, one more command in the same trip.
     * Empty for someone who is not in the room.
     */
    public Optional<ChatState> state(UUID roomId, UUID userId, boolean host) {
        RedisAsyncCommands<String, String> redis = connection.async();
        RedisFuture<Double> joined = redis.zscore(PresenceRepository.key(roomId), userId.toString());
        RedisFuture<List<String>> comments = redis.lrange(commentsKey(roomId), 0, -1);
        RedisFuture<String> topic = redis.get(topicKey(roomId));
        RedisFuture<Boolean> muted = redis.sismember(mutedKey(roomId), userId.toString());
        RedisFuture<Set<String>> everyoneMuted = host ? redis.smembers(mutedKey(roomId)) : null;
        await(host ? new RedisFuture<?>[]{joined, comments, topic, muted, everyoneMuted} : new RedisFuture<?>[]{joined, comments, topic, muted});

        Double lastSeen = joined.toCompletableFuture().join();
        if (lastSeen == null || lastSeen < presence.freshnessCutoff().toEpochMilli()) {
            return Optional.empty();
        }

        List<RoomComment> oldestFirst = new ArrayList<>();
        List<String> newestFirst = comments.toCompletableFuture().join();
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            read(roomId, newestFirst.get(i)).ifPresent(oldestFirst::add);
        }
        List<UUID> mutedUsers = host ? everyoneMuted.toCompletableFuture().join().stream().map(UUID::fromString).toList() : List.of();
        return Optional.of(new ChatState(oldestFirst, topic.toCompletableFuture().join(), muted.toCompletableFuture().join(), mutedUsers));
    }

    /** Removes the comment; false if there was none with that id. Matched by its id, not by text that may appear in a reply to it. */
    public boolean delete(UUID roomId, UUID commentId) {
        for (String raw : connection.sync().lrange(commentsKey(roomId), 0, -1)) {
            if (read(roomId, raw).filter(comment -> comment.id().equals(commentId)).isPresent()) {
                return connection.sync().lrem(commentsKey(roomId), 1, raw) > 0;
            }
        }
        return false;
    }

    public void mute(UUID roomId, UUID userId) {
        RedisAsyncCommands<String, String> redis = connection.async();
        await(redis.sadd(mutedKey(roomId), userId.toString()), redis.expire(mutedKey(roomId), ttlSeconds));
    }

    public void unmute(UUID roomId, UUID userId) {
        connection.sync().srem(mutedKey(roomId), userId.toString());
    }

    public void pinTopic(UUID roomId, String topic) {
        connection.sync().setex(topicKey(roomId), ttlSeconds, topic);
    }

    public void clearTopic(UUID roomId) {
        connection.sync().del(topicKey(roomId));
    }

    /** The chat of a room that has closed. (The per-person counters expire with their window.) */
    public void clear(UUID roomId) {
        connection.sync().del(commentsKey(roomId), topicKey(roomId), mutedKey(roomId));
    }

    private String write(RoomComment comment) {
        try {
            return json.writeValueAsString(comment);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Optional<RoomComment> read(UUID roomId, String raw) {
        try {
            return Optional.of(json.readValue(raw, RoomComment.class));
        } catch (IOException e) {
            log.warn("Skipping a comment in room {} that could not be read ({})", roomId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String commentsKey(UUID roomId) {
        return roomKey(roomId, "comments");
    }

    private static String topicKey(UUID roomId) {
        return roomKey(roomId, "topic");
    }

    private static String mutedKey(UUID roomId) {
        return roomKey(roomId, "chat-muted");
    }

    private static String rateKey(UUID roomId, UUID userId) {
        return roomKey(roomId, "chat-rate:" + userId);
    }
}
