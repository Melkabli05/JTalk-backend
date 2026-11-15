package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.dto.request.NetworkModeRequest;
import com.jtalk.livecommunication.dto.response.RoomSessionResponse;
import com.jtalk.livecommunication.dto.response.StageResponse;
import com.jtalk.livecommunication.service.StageService;
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
    RoomSessionResponse claimStageSeat(UUID roomId, @CurrentUser UUID userId) {
        return stage.claimStageSeat(roomId, userId);
    }

    @Post("/leave")
    @Status(HttpStatus.NO_CONTENT)
    void leaveStageSeat(UUID roomId, @CurrentUser UUID userId) {
        stage.leaveStageSeat(roomId, userId);
    }

    @Post("/raise-hand")
    @Status(HttpStatus.ACCEPTED)
    void raiseHand(UUID roomId, @CurrentUser UUID userId) {
        stage.raiseHand(roomId, userId);
    }

    @Delete("/raise-hand")
    @Status(HttpStatus.NO_CONTENT)
    void lowerHand(UUID roomId, @CurrentUser UUID userId) {
        stage.lowerHand(roomId, userId);
    }

    /** Host puts a user on the stage. */
    @Post("/promote/{targetUserId}")
    @Status(HttpStatus.NO_CONTENT)
    void promoteToStage(UUID roomId, UUID targetUserId, @CurrentUser UUID userId) {
        stage.promoteToStage(roomId, userId, targetUserId);
    }

    /** Host steps a speaker down to the audience. */
    @Post("/demote/{targetUserId}")
    @Status(HttpStatus.NO_CONTENT)
    void demoteToListener(UUID roomId, UUID targetUserId, @CurrentUser UUID userId) {
        stage.demoteToListener(roomId, userId, targetUserId);
    }

    @Post("/network-mode")
    @Status(HttpStatus.NO_CONTENT)
    void updateNetworkMode(UUID roomId, @CurrentUser UUID userId, @Body @Valid NetworkModeRequest request) {
        stage.updateNetworkMode(roomId, userId, request);
    }
}
