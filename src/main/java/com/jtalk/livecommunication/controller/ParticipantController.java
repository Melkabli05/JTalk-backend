package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.dto.response.ParticipantResponse;
import com.jtalk.livecommunication.dto.response.RoomSessionResponse;
import com.jtalk.livecommunication.domain.model.AudiencePage;
import com.jtalk.livecommunication.service.ParticipantService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.annotation.Status;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.UUID;

@Controller("/api/v1/voice-rooms/{roomId}/participants")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.VIRTUAL)
@RequiredArgsConstructor
class ParticipantController {

    private final ParticipantService participants;

    @Post("/join")
    RoomSessionResponse joinRoom(UUID roomId, @CurrentUser UUID userId) {
        return participants.joinRoom(roomId, userId);
    }

    @Post("/leave")
    @Status(HttpStatus.NO_CONTENT)
    void leaveRoom(UUID roomId, @CurrentUser UUID userId) {
        participants.leaveRoom(roomId, userId);
    }

    @Get
    List<ParticipantResponse> listParticipants(UUID roomId) {
        return participants.listParticipants(roomId);
    }

    @Get("/audience")
    AudiencePage audience(UUID roomId, @CurrentUser UUID userId, @Nullable @QueryValue UUID cursor) {
        return participants.audience(roomId, userId, cursor);
    }

    /** Host moderation: removes the participant from the room and bars them from rejoining. */
    @Delete("/{targetUserId}")
    @Status(HttpStatus.NO_CONTENT)
    void kickParticipant(UUID roomId, UUID targetUserId, @CurrentUser UUID userId) {
        participants.kickParticipant(roomId, userId, targetUserId);
    }
}
