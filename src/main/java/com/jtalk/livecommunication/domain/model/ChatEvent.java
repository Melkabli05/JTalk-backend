package com.jtalk.livecommunication.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jtalk.livecommunication.domain.enums.ChatEventType;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.UUID;

/**
 * A change to a room's chat, pushed to everyone in the room on its own data channel topic, apart from the room's state events so a burst
 * of comments never queues behind a stage change. Which fields are set depends on the type.
 *
 * @param comment      the comment, for {@code COMMENT_POSTED}
 * @param commentId    the comment that went, for {@code COMMENT_DELETED}
 * @param targetUserId who was muted or unmuted, for {@code CHAT_MUTED} and {@code CHAT_UNMUTED}
 * @param topic        the pinned topic, for {@code TOPIC_CHANGED}; absent when it was cleared
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Serdeable
public record ChatEvent(ChatEventType type, UUID roomId, Instant timestamp, @Nullable RoomComment comment, @Nullable UUID commentId,
                        @Nullable UUID targetUserId, @Nullable String topic) {

    public static ChatEvent posted(UUID roomId, Instant at, RoomComment comment) {
        return new ChatEvent(ChatEventType.COMMENT_POSTED, roomId, at, comment, null, null, null);
    }

    public static ChatEvent deleted(UUID roomId, Instant at, UUID commentId) {
        return new ChatEvent(ChatEventType.COMMENT_DELETED, roomId, at, null, commentId, null, null);
    }

    public static ChatEvent muted(UUID roomId, Instant at, UUID userId) {
        return new ChatEvent(ChatEventType.CHAT_MUTED, roomId, at, null, null, userId, null);
    }

    public static ChatEvent unmuted(UUID roomId, Instant at, UUID userId) {
        return new ChatEvent(ChatEventType.CHAT_UNMUTED, roomId, at, null, null, userId, null);
    }

    public static ChatEvent topic(UUID roomId, Instant at, @Nullable String topic) {
        return new ChatEvent(ChatEventType.TOPIC_CHANGED, roomId, at, null, null, null, topic);
    }
}
