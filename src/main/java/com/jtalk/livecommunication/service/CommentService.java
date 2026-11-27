package com.jtalk.livecommunication.service;

import com.jtalk.livecommunication.domain.enums.CommentType;
import com.jtalk.livecommunication.domain.enums.Reaction;
import com.jtalk.livecommunication.domain.model.ChatState;
import com.jtalk.livecommunication.domain.model.RoomComment;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;

import java.util.UUID;

/** A room's chat: comments and corrections from the people in it, a topic and the host's moderation. Kept in Redis, told to the room as events. */
public interface CommentService {

    /** The last comments, the pinned topic and whether this person is muted; the host also gets who is. Only for someone in the room: 403 {@code not-in-room}. */
    ChatState chat(@NonNull UUID roomId, @NonNull UUID userId);

    /**
     * Posts a comment as someone who is in the room (checked in the same Redis script that keeps the comment: one round trip). 400 {@code invalid-comment} for an empty one (once control characters are gone) or a
     * correction without its corrected phrase, 403 {@code not-in-room}, 403 {@code chat-muted}, 429 {@code commenting-too-fast}.
     */
    default RoomComment post(@NonNull UUID roomId, @NonNull UUID userId, @NonNull CommentType type, @NonNull String content,
                             @Nullable String correctedContent, @Nullable UUID replyTo) {
        return post(roomId, userId, type, content, correctedContent, replyTo, null);
    }

    RoomComment post(@NonNull UUID roomId, @NonNull UUID userId, @NonNull CommentType type, @NonNull String content,
                     @Nullable String correctedContent, @Nullable UUID replyTo, @Nullable String tip);

    /**
     * Makes {@code reaction} this person's reaction to the comment, replacing the one they had (a person has one per comment); the room is told
     * with the change. Nothing happens, and nothing is told, if it already is their reaction. 404 {@code comment-not-found} when the comment is
     * gone, 403 {@code not-in-room}, 403 {@code chat-muted}, 429 {@code reacting-too-fast}.
     */
    void react(@NonNull UUID roomId, @NonNull UUID userId, @NonNull UUID commentId, @NonNull Reaction reaction);

    /** Takes back the person's reaction to the comment, on the same terms; nothing happens if they had none. */
    void unreact(@NonNull UUID roomId, @NonNull UUID userId, @NonNull UUID commentId);

    /** Host-only. Removes the comment for everyone; nothing happens if it is already gone. */
    void delete(@NonNull UUID roomId, @NonNull UUID hostId, @NonNull UUID commentId);

    /** Host-only. The person keeps their place in the room but cannot comment. The host cannot be muted: 422 {@code cannot-mute-host}. */
    void mute(@NonNull UUID roomId, @NonNull UUID hostId, @NonNull UUID targetUserId);

    /** Host-only. */
    void unmute(@NonNull UUID roomId, @NonNull UUID hostId, @NonNull UUID targetUserId);

    /** Host-only. Pins a topic at the top of the chat, replacing the one there. */
    void pinTopic(@NonNull UUID roomId, @NonNull UUID hostId, @NonNull String topic);

    /** Host-only. */
    void clearTopic(@NonNull UUID roomId, @NonNull UUID hostId);

    /** Host-only. Pins a comment, marking it with the current timestamp. 404 {@code comment-not-found} if the comment is gone. */
    void pinComment(@NonNull UUID roomId, @NonNull UUID hostId, @NonNull UUID commentId);

    /** Host-only. Unpins a comment, clearing its pinned timestamp. 404 {@code comment-not-found} if the comment is gone. */
    void unpinComment(@NonNull UUID roomId, @NonNull UUID hostId, @NonNull UUID commentId);
}
