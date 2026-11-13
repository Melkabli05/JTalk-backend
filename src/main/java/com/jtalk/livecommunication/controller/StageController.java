package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.domain.dto.request.NetworkModeRequest;
import com.jtalk.livecommunication.domain.dto.request.WhisperRequest;
import com.jtalk.livecommunication.domain.dto.response.RoomSessionResponse;
import com.jtalk.livecommunication.domain.dto.response.StageResponse;
import com.jtalk.livecommunication.domain.service.StageService;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Status;
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
    @Status(HttpStatus.NO_CONTENT)
    void leaveStageSeat(UUID roomId, Principal principal) {
        stage.leaveStageSeat(roomId, UUID.fromString(principal.getName()));
    }

    @Post("/raise-hand")
    @Status(HttpStatus.ACCEPTED)
    void raiseHand(UUID roomId, Principal principal) {
        stage.raiseHand(roomId, UUID.fromString(principal.getName()));
    }

    @Delete("/raise-hand")
    @Status(HttpStatus.NO_CONTENT)
    void lowerHand(UUID roomId, Principal principal) {
        stage.lowerHand(roomId, UUID.fromString(principal.getName()));
    }

    /** Host puts a user on the stage. */
    @Post("/promote/{targetUserId}")
    @Status(HttpStatus.NO_CONTENT)
    void promoteToStage(UUID roomId, UUID targetUserId, Principal principal) {
        stage.promoteToStage(roomId, UUID.fromString(principal.getName()), targetUserId);
    }

    /** Host steps a speaker down to the audience. */
    @Post("/demote/{targetUserId}")
    @Status(HttpStatus.NO_CONTENT)
    void demoteToListener(UUID roomId, UUID targetUserId, Principal principal) {
        stage.demoteToListener(roomId, UUID.fromString(principal.getName()), targetUserId);
    }

    @Post("/whisper")
    @Status(HttpStatus.NO_CONTENT)
    void startWhisper(UUID roomId, Principal principal, @Body @Valid WhisperRequest request) {
        stage.startWhisper(roomId, UUID.fromString(principal.getName()), request);
    }

    @Delete("/whisper")
    @Status(HttpStatus.NO_CONTENT)
    void stopWhisper(UUID roomId, Principal principal) {
        stage.stopWhisper(roomId, UUID.fromString(principal.getName()));
    }

    @Post("/network-mode")
    @Status(HttpStatus.NO_CONTENT)
    void updateNetworkMode(UUID roomId, Principal principal, @Body @Valid NetworkModeRequest request) {
        stage.updateNetworkMode(roomId, UUID.fromString(principal.getName()), request);
    }
}
