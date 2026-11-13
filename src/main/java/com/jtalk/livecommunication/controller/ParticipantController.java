package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.domain.dto.response.ParticipantResponse;
import com.jtalk.livecommunication.domain.dto.response.RoomSessionResponse;
import com.jtalk.livecommunication.domain.service.ParticipantService;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Status;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import lombok.RequiredArgsConstructor;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@Controller("/api/v1/voice-rooms/{roomId}/participants")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.VIRTUAL)
@RequiredArgsConstructor
class ParticipantController {

    private final ParticipantService participants;

    @Post("/join")
    RoomSessionResponse joinRoom(UUID roomId, Principal principal) {
        return participants.joinRoom(roomId, UUID.fromString(principal.getName()));
    }

    @Post("/leave")
    @Status(HttpStatus.NO_CONTENT)
    void leaveRoom(UUID roomId, Principal principal) {
        participants.leaveRoom(roomId, UUID.fromString(principal.getName()));
    }

    @Post("/heartbeat")
    @Status(HttpStatus.NO_CONTENT)
    void heartbeat(UUID roomId, Principal principal) {
        participants.heartbeat(roomId, UUID.fromString(principal.getName()));
    }

    @Get
    List<ParticipantResponse> listParticipants(UUID roomId) {
        return participants.listParticipants(roomId);
    }

    /** Host moderation: removes the participant from the room and bars them from rejoining. */
    @Delete("/{targetUserId}")
    @Status(HttpStatus.NO_CONTENT)
    void kickParticipant(UUID roomId, UUID targetUserId, Principal principal) {
        participants.kickParticipant(roomId, UUID.fromString(principal.getName()), targetUserId);
    }
}
