package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.dto.request.ReactionRequest;
import com.jtalk.livecommunication.service.ReactionService;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
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

@Controller("/api/v1/voice-rooms/{roomId}/reactions")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.VIRTUAL)
@Validated
@RequiredArgsConstructor
class ReactionController {

    private final ReactionService reactions;

    @Post
    @Status(HttpStatus.ACCEPTED)
    void react(UUID roomId, @CurrentUser UUID userId, @Body @Valid ReactionRequest request) {
        reactions.react(roomId, userId, request.reaction());
    }
}
