package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.domain.dto.response.HandRaiseResponse;
import com.jtalk.livecommunication.domain.dto.response.StageResponse;
import com.jtalk.livecommunication.domain.service.StageService;
import com.jtalk.livecommunication.domain.mapper.ApiMapper;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.validation.Validated;
import lombok.RequiredArgsConstructor;

import java.security.Principal;
import java.util.UUID;

@Controller("/api/v1/voice-rooms/{roomId}/stage")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.VIRTUAL)
@Validated
@RequiredArgsConstructor
class StageController {

    private final StageService stage;
    private final ApiMapper mapper;

    @Get
    StageResponse get(UUID roomId) {
        return mapper.toResponse(stage.getStage(roomId));
    }

    @Post("/hand-raises")
    HandRaiseResponse raiseHand(UUID roomId, Principal principal) {
        return new HandRaiseResponse(stage.raiseHand(roomId, principal.getName()));
    }

    @Delete("/hand-raises/me")
    HttpResponse<Void> lowerHand(UUID roomId, Principal principal) {
        stage.lowerHand(roomId, principal.getName());
        return HttpResponse.noContent();
    }

    /** Host approves a raised hand. */
    @Put("/speakers/{speakerId}")
    HttpResponse<Void> approve(UUID roomId, String speakerId, Principal principal) {
        stage.approveSpeaker(roomId, principal.getName(), speakerId);
        return HttpResponse.noContent();
    }

    /** A speaker steps down (own id) or the host removes a speaker. */
    @Delete("/speakers/{speakerId}")
    HttpResponse<Void> remove(UUID roomId, String speakerId, Principal principal) {
        stage.removeSpeaker(roomId, principal.getName(), speakerId);
        return HttpResponse.noContent();
    }
}
