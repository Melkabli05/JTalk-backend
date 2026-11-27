package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.service.StageService;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Status;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

@Controller("/api/v1/voice-rooms/{roomId}/moderators")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.VIRTUAL)
@RequiredArgsConstructor
class ModeratorController {

    private final StageService stage;

    @Post("/{targetUserId}")
    @Status(HttpStatus.NO_CONTENT)
    void assign(UUID roomId, UUID targetUserId, @CurrentUser UUID userId) {
        stage.setModerator(roomId, userId, targetUserId, true);
    }

    @Delete("/{targetUserId}")
    @Status(HttpStatus.NO_CONTENT)
    void revoke(UUID roomId, UUID targetUserId, @CurrentUser UUID userId) {
        stage.setModerator(roomId, userId, targetUserId, false);
    }
}
