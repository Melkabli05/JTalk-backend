package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.domain.model.ChatState;
import com.jtalk.livecommunication.domain.model.RoomComment;
import com.jtalk.livecommunication.dto.request.PinTopicRequest;
import com.jtalk.livecommunication.dto.request.PostCommentRequest;
import com.jtalk.livecommunication.service.CommentService;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.Status;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.validation.Validated;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

@Controller("/api/v1/voice-rooms/{roomId}/comments")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.VIRTUAL)
@Validated
@RequiredArgsConstructor
class CommentController {

    private final CommentService comments;

    /** The chat as the caller opens it: the last comments, oldest first, the pinned topic, and whether they are muted. */
    @Get
    ChatState chat(UUID roomId, @CurrentUser UUID userId) {
        return comments.chat(roomId, userId);
    }

    @Post
    @Status(HttpStatus.CREATED)
    RoomComment post(UUID roomId, @CurrentUser UUID userId, @Body @Valid PostCommentRequest request) {
        return comments.post(roomId, userId, request.type(), request.content(), request.correctedContent(), request.replyTo());
    }

    @Delete("/{commentId}")
    @Status(HttpStatus.NO_CONTENT)
    void delete(UUID roomId, UUID commentId, @CurrentUser UUID userId) {
        comments.delete(roomId, userId, commentId);
    }

    @Post("/mute/{targetUserId}")
    @Status(HttpStatus.NO_CONTENT)
    void mute(UUID roomId, UUID targetUserId, @CurrentUser UUID userId) {
        comments.mute(roomId, userId, targetUserId);
    }

    @Delete("/mute/{targetUserId}")
    @Status(HttpStatus.NO_CONTENT)
    void unmute(UUID roomId, UUID targetUserId, @CurrentUser UUID userId) {
        comments.unmute(roomId, userId, targetUserId);
    }

    @Put("/topic")
    @Status(HttpStatus.NO_CONTENT)
    void pinTopic(UUID roomId, @CurrentUser UUID userId, @Body @Valid PinTopicRequest request) {
        comments.pinTopic(roomId, userId, request.text());
    }

    @Delete("/topic")
    @Status(HttpStatus.NO_CONTENT)
    void clearTopic(UUID roomId, @CurrentUser UUID userId) {
        comments.clearTopic(roomId, userId);
    }
}
