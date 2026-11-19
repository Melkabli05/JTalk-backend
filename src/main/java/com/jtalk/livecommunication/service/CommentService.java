package com.jtalk.livecommunication.service;

import com.jtalk.livecommunication.domain.enums.CommentType;
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
    RoomComment post(@NonNull UUID roomId, @NonNull UUID userId, @NonNull CommentType type, @NonNull String content,
                     @Nullable String correctedContent, @Nullable UUID replyTo);

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
}
