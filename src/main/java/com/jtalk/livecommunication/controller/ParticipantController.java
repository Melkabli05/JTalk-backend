package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.domain.dto.response.JoinRoomResponse;
import com.jtalk.livecommunication.domain.dto.response.ParticipantResponse;
import com.jtalk.livecommunication.domain.service.ParticipantService;
import com.jtalk.livecommunication.domain.mapper.ApiMapper;
import com.jtalk.livecommunication.security.CallerId;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.validation.Validated;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.UUID;

@Controller("/api/v1/voice-rooms/{roomId}/participants")
@ExecuteOn(TaskExecutors.VIRTUAL)
@Validated
@RequiredArgsConstructor
class ParticipantController {

    private final ParticipantService participants;
    private final ApiMapper mapper;

    @Post("/me")
    JoinRoomResponse join(UUID roomId, @CallerId String userId) {
        return mapper.toResponse(participants.join(roomId, userId));
    }

    @Post("/me/heartbeat")
    HttpResponse<Void> heartbeat(UUID roomId, @CallerId String userId) {
        participants.heartbeat(roomId, userId);
        return HttpResponse.noContent();
    }

    @Delete("/me")
    HttpResponse<Void> leave(UUID roomId, @CallerId String userId) {
        participants.leave(roomId, userId);
        return HttpResponse.noContent();
    }

    @Get
    List<ParticipantResponse> list(UUID roomId) {
        return mapper.toParticipantResponses(participants.listPresent(roomId));
    }
}
