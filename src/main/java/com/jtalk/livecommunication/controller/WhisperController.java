package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.domain.dto.request.WhisperRequest;
import com.jtalk.livecommunication.domain.service.WhisperService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.validation.Validated;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import java.security.Principal;
import java.util.UUID;

@Controller("/api/v1/voice-rooms/{roomId}/whisper")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.VIRTUAL)
@Validated
@RequiredArgsConstructor
class WhisperController {

    private final WhisperService whispers;

    @Post
    HttpResponse<Void> start(UUID roomId,
                             Principal principal,
                             @Body @Valid WhisperRequest request) {
        whispers.start(roomId, principal.getName(), request.targetUserId());
        return HttpResponse.noContent();
    }

    @Delete
    HttpResponse<Void> stop(UUID roomId, Principal principal) {
        whispers.stop(roomId, principal.getName());
        return HttpResponse.noContent();
    }
}
