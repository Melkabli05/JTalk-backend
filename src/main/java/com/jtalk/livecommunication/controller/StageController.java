package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.domain.dto.request.NetworkModeRequest;
import com.jtalk.livecommunication.domain.dto.request.WhisperRequest;
import com.jtalk.livecommunication.domain.dto.response.RoomSessionResponse;
import com.jtalk.livecommunication.domain.dto.response.StageResponse;
import com.jtalk.livecommunication.domain.service.StageService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.validation.Validated;
import jakarta.validation.Valid;
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

    @Get
    StageResponse getStage(UUID roomId) {
        return stage.getStage(roomId);
    }

    @Post("/join")
    RoomSessionResponse claimStageSeat(UUID roomId, Principal principal) {
        return stage.claimStageSeat(roomId, UUID.fromString(principal.getName()));
    }

    @Post("/leave")
    HttpResponse<Void> leaveStageSeat(UUID roomId, Principal principal) {
        stage.leaveStageSeat(roomId, UUID.fromString(principal.getName()));
        return HttpResponse.noContent();
    }

    @Post("/raise-hand")
    HttpResponse<Void> raiseHand(UUID roomId, Principal principal) {
        stage.raiseHand(roomId, UUID.fromString(principal.getName()));
        return HttpResponse.accepted();
    }

    @Delete("/raise-hand")
    HttpResponse<Void> lowerHand(UUID roomId, Principal principal) {
        stage.lowerHand(roomId, UUID.fromString(principal.getName()));
        return HttpResponse.noContent();
    }

    /** Host puts a user on the stage. */
    @Post("/promote/{targetUserId}")
    HttpResponse<Void> promoteToStage(UUID roomId, UUID targetUserId, Principal principal) {
        stage.promoteToStage(roomId, UUID.fromString(principal.getName()), targetUserId);
        return HttpResponse.noContent();
    }

    /** Host steps a speaker down to the audience. */
    @Post("/demote/{targetUserId}")
    HttpResponse<Void> demoteToListener(UUID roomId, UUID targetUserId, Principal principal) {
        stage.demoteToListener(roomId, UUID.fromString(principal.getName()), targetUserId);
        return HttpResponse.noContent();
    }

    @Post("/whisper")
    HttpResponse<Void> startWhisper(UUID roomId, Principal principal, @Body @Valid WhisperRequest request) {
        stage.startWhisper(roomId, UUID.fromString(principal.getName()), request);
        return HttpResponse.noContent();
    }

    @Delete("/whisper")
    HttpResponse<Void> stopWhisper(UUID roomId, Principal principal) {
        stage.stopWhisper(roomId, UUID.fromString(principal.getName()));
        return HttpResponse.noContent();
    }

    @Post("/network-mode")
    HttpResponse<Void> updateNetworkMode(UUID roomId, Principal principal, @Body @Valid NetworkModeRequest request) {
        stage.updateNetworkMode(roomId, UUID.fromString(principal.getName()), request);
        return HttpResponse.noContent();
    }
}
