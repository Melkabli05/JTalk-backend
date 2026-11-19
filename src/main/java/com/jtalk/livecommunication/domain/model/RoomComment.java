package com.jtalk.livecommunication.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jtalk.livecommunication.domain.enums.CommentType;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.UUID;

/**
 * One comment in a room's chat. It carries who wrote it, not their name or picture: those live in the user service, and the clients look
 * them up as they do for everyone else in the room, so nothing about a person is stored with the comment.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Serdeable
public record RoomComment(UUID id, UUID authorId, CommentType type, String content, @Nullable String correctedContent,
                          @Nullable UUID replyTo, Instant createdAt, @Nullable Instant pinnedAt) {
}
