package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.domain.dto.response.JoinRoomResponse;
import com.jtalk.livecommunication.domain.dto.response.ParticipantResponse;
import com.jtalk.livecommunication.domain.service.ParticipantService;
import com.jtalk.livecommunication.domain.mapper.ApiMapper;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.validation.Validated;
import lombok.RequiredArgsConstructor;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@Controller("/api/v1/voice-rooms/{roomId}/participants")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.VIRTUAL)
@Validated
@RequiredArgsConstructor
class ParticipantController {

    private final ParticipantService participants;
    private final ApiMapper mapper;

    @Post("/me")
    JoinRoomResponse join(UUID roomId, Principal principal) {
        return mapper.toResponse(participants.join(roomId, principal.getName()));
    }

    @Post("/me/heartbeat")
    HttpResponse<Void> heartbeat(UUID roomId, Principal principal) {
        participants.heartbeat(roomId, principal.getName());
        return HttpResponse.noContent();
    }

    @Delete("/me")
    HttpResponse<Void> leave(UUID roomId, Principal principal) {
        participants.leave(roomId, principal.getName());
        return HttpResponse.noContent();
    }

    @Get
    List<ParticipantResponse> list(UUID roomId) {
        return mapper.toParticipantResponses(participants.listPresent(roomId));
    }
}
