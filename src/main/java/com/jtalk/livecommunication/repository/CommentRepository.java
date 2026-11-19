package com.jtalk.livecommunication.repository;

import com.jtalk.livecommunication.config.VoiceRoomProperties;
import com.jtalk.livecommunication.domain.enums.Reaction;
import com.jtalk.livecommunication.domain.model.ChatState;
import com.jtalk.livecommunication.domain.model.CommentReactions;
import com.jtalk.livecommunication.domain.model.RoomComment;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 *   <li>{@code voiceroom:{id}:reaction-counts} - HASH of {@code commentId|REACTION} to how many people chose it</li>
 *   <li>{@code voiceroom:{id}:reactions-by:{userId}} - HASH of comment to the reaction this person chose (it is what they are told is theirs)</li>
 * </ul>
 */
@Singleton
@Slf4j
public class CommentRepository extends RedisRepository {

    /** What posting came to. */
    public enum Posting {
        KEPT, NOT_IN_ROOM, MUTED, TOO_FAST
    }

    /** What reacting came to: {@code previous} is what the person reacted with before, when it changed. */
    public record Reacted(Outcome outcome, @Nullable Reaction previous) {

        public enum Outcome {
            CHANGED, UNCHANGED, NOT_IN_ROOM, MUTED, NO_SUCH_COMMENT
        }
    }

    static final int KEPT = 50;

    private final PresenceRepository presence;
    private final ObjectMapper json;
    private final LuaScript postScript;
    private final LuaScript reactScript;
    private final long ttlSeconds;
    private final int perWindow;
    private final long windowSeconds;

    public CommentRepository(StatefulRedisConnection<String, String> connection, PresenceRepository presence, ObjectMapper json,
                             ResourceResolver resources, VoiceRoomProperties properties) {
        super(connection);
        this.presence = presence;
        this.json = json;
        this.postScript = LuaScript.load(resources, "lua/post_comment.lua");
        this.reactScript = LuaScript.load(resources, "lua/react_comment.lua");
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
     * Sets, changes or (with no reaction) takes back the person's reaction to a comment, again in one script call: in the room, not muted and
     * the comment still there, or nothing changes.
     */
    public Reacted react(UUID roomId, UUID userId, UUID commentId, @Nullable Reaction reaction) {
        List<Object> result = reactScript.execute(connection.sync(), ScriptOutputType.MULTI,
                new String[]{PresenceRepository.key(roomId), mutedKey(roomId), commentsKey(roomId), reactionCountsKey(roomId), reactionsByKey(roomId, userId)},
                userId.toString(), commentId.toString(), reaction == null ? "" : reaction.name(), Long.toString(ttlSeconds),
                Long.toString(presence.freshnessCutoff().toEpochMilli()));
        return switch (((Long) result.get(0)).intValue()) {
            case -3 -> new Reacted(Reacted.Outcome.NOT_IN_ROOM, null);
            case -1 -> new Reacted(Reacted.Outcome.MUTED, null);
            case -4 -> new Reacted(Reacted.Outcome.NO_SUCH_COMMENT, null);
            case 0 -> new Reacted(Reacted.Outcome.UNCHANGED, null);
            default -> new Reacted(Reacted.Outcome.CHANGED, reactionOf((String) result.get(1)));
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
        RedisFuture<Map<String, String>> counts = redis.hgetall(reactionCountsKey(roomId));
        RedisFuture<Map<String, String>> mine = redis.hgetall(reactionsByKey(roomId, userId));
        await(host ? new RedisFuture<?>[]{joined, comments, topic, muted, counts, mine, everyoneMuted}
                : new RedisFuture<?>[]{joined, comments, topic, muted, counts, mine});

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
        return Optional.of(new ChatState(oldestFirst, topic.toCompletableFuture().join(), muted.toCompletableFuture().join(), mutedUsers,
                reactionsTo(oldestFirst, counts.toCompletableFuture().join(), mine.toCompletableFuture().join())));
    }

    /** What each of these comments was reacted to with, as this person sees it. Comments nobody reacted to have no entry. */
    private static List<CommentReactions> reactionsTo(List<RoomComment> shown, Map<String, String> counts, Map<String, String> mine) {
        Map<UUID, Map<Reaction, Integer>> byComment = new HashMap<>();
        counts.forEach((field, people) -> {
            int split = field.indexOf('|');
            Reaction reaction = split < 0 ? null : reactionOf(field.substring(split + 1));
            if (reaction != null) {
                byComment.computeIfAbsent(UUID.fromString(field.substring(0, split)), id -> new EnumMap<>(Reaction.class))
                        .put(reaction, Integer.parseInt(people));
            }
        });
        return shown.stream()
                .filter(comment -> byComment.containsKey(comment.id()))
                .map(comment -> new CommentReactions(comment.id(), byComment.get(comment.id()), reactionOf(mine.get(comment.id().toString()))))
                .toList();
    }

    /** Removes the comment; false if there was none with that id. Matched by its id, not by text that may appear in a reply to it. */
    public boolean delete(UUID roomId, UUID commentId) {
        for (String raw : connection.sync().lrange(commentsKey(roomId), 0, -1)) {
            if (read(roomId, raw).filter(comment -> comment.id().equals(commentId)).isPresent()) {
                connection.sync().hdel(reactionCountsKey(roomId), Arrays.stream(Reaction.values()).map(reaction -> commentId + "|" + reaction).toArray(String[]::new));
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

    /** The chat of a room that has closed. (The per-person counters and what each person reacted with expire on their own.) */
    public void clear(UUID roomId) {
        connection.sync().del(commentsKey(roomId), topicKey(roomId), mutedKey(roomId), reactionCountsKey(roomId));
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

    private static String reactionCountsKey(UUID roomId) {
        return roomKey(roomId, "reaction-counts");
    }

    private static String reactionsByKey(UUID roomId, UUID userId) {
        return roomKey(roomId, "reactions-by:" + userId);
    }

    /** The reaction a stored name stands for; none for an empty or unknown one (a name this version no longer has). */
    private static @Nullable Reaction reactionOf(@Nullable String name) {
        try {
            return name == null || name.isEmpty() ? null : Reaction.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static String rateKey(UUID roomId, UUID userId) {
        return roomKey(roomId, "chat-rate:" + userId);
    }
}
