package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.domain.dto.request.WhisperRequest;
import com.jtalk.livecommunication.domain.service.WhisperService;
import com.jtalk.livecommunication.security.RequestHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import io.micronaut.validation.Validated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

@Controller("/api/v1/voice-rooms/{roomId}/whisper")
@ExecuteOn(TaskExecutors.VIRTUAL)
@Validated
class WhisperController {

    private final WhisperService whispers;

    WhisperController(WhisperService whispers) {
        this.whispers = whispers;
    }

    @Post
    HttpResponse<Void> start(UUID roomId,
                             @Header(RequestHeaders.USER_ID) @NotBlank String userId,
                             @Body @Valid WhisperRequest request) {
        whispers.start(roomId, userId, request.targetUserId());
        return HttpResponse.noContent();
    }

    @Delete
    HttpResponse<Void> stop(UUID roomId, @Header(RequestHeaders.USER_ID) @NotBlank String userId) {
        whispers.stop(roomId, userId);
        return HttpResponse.noContent();
    }
}
