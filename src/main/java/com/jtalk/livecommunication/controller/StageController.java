package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.domain.dto.response.HandRaiseResponse;
import com.jtalk.livecommunication.domain.dto.response.StageResponse;
import com.jtalk.livecommunication.domain.service.StageService;
import com.jtalk.livecommunication.security.RequestHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.validation.Validated;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

@Controller("/api/v1/voice-rooms/{roomId}/stage")
@ExecuteOn(TaskExecutors.VIRTUAL)
@Validated
class StageController {

    private final StageService stage;

    StageController(StageService stage) {
        this.stage = stage;
    }

    @Get
    StageResponse get(UUID roomId) {
        return StageResponse.from(stage.getStage(roomId));
    }

    @Post("/hand-raises")
    HandRaiseResponse raiseHand(UUID roomId, @Header(RequestHeaders.USER_ID) @NotBlank String userId) {
        return new HandRaiseResponse(stage.raiseHand(roomId, userId));
    }

    @Delete("/hand-raises/me")
    HttpResponse<Void> lowerHand(UUID roomId, @Header(RequestHeaders.USER_ID) @NotBlank String userId) {
        stage.lowerHand(roomId, userId);
        return HttpResponse.noContent();
    }

    /** Host approves a raised hand. */
    @Put("/speakers/{speakerId}")
    HttpResponse<Void> approve(UUID roomId, String speakerId, @Header(RequestHeaders.USER_ID) @NotBlank String userId) {
        stage.approveSpeaker(roomId, userId, speakerId);
        return HttpResponse.noContent();
    }

    /** A speaker steps down (own id) or the host removes a speaker. */
    @Delete("/speakers/{speakerId}")
    HttpResponse<Void> remove(UUID roomId, String speakerId, @Header(RequestHeaders.USER_ID) @NotBlank String userId) {
        stage.removeSpeaker(roomId, userId, speakerId);
        return HttpResponse.noContent();
    }
}
