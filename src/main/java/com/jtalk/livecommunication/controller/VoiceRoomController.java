package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.dto.request.CreateVoiceRoomRequest;
import com.jtalk.livecommunication.dto.request.UpdateVoiceRoomRequest;
import com.jtalk.livecommunication.dto.response.RoomSessionResponse;
import com.jtalk.livecommunication.dto.response.VoiceRoomResponse;
import com.jtalk.livecommunication.service.VoiceRoomService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Patch;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.annotation.Status;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.validation.Validated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

import static com.jtalk.livecommunication.config.CommunicationConstants.MAX_PAGE_SIZE;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

@Controller("/api/v1/voice-rooms")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.VIRTUAL)
@Validated
@RequiredArgsConstructor
class VoiceRoomController {

    private final VoiceRoomService rooms;

    @Post
    HttpResponse<RoomSessionResponse> createRoom(@CurrentUser UUID userId, @Body @Valid CreateVoiceRoomRequest request) {
        RoomSessionResponse session = rooms.createRoom(userId, request);
        return HttpResponse.created(session, URI.create("/api/v1/voice-rooms/" + session.room().id()));
    }

    /**
     * Keyset pagination: the first page needs no cursor; for the next one pass the last room's {@code createdAt} and
     * {@code id} as {@code afterCreatedAt} and {@code afterId}. Micronaut binds the values, no cursor token needed.
     */
    @Get
    CursoredPage<VoiceRoomResponse> listActiveRooms(@Nullable @QueryValue String targetLanguage,
                                                    @QueryValue(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
                                                    @Nullable @QueryValue Instant afterCreatedAt,
                                                    @Nullable @QueryValue UUID afterId) {
        return rooms.listActiveRooms(targetLanguage, size, afterCreatedAt, afterId);
    }

    @Get("/{roomId}")
    VoiceRoomResponse getRoom(UUID roomId) {
        return rooms.getRoom(roomId);
    }

    @Patch("/{roomId}")
    VoiceRoomResponse updateRoom(UUID roomId, @CurrentUser UUID userId, @Body @Valid UpdateVoiceRoomRequest request) {
        return rooms.updateRoom(roomId, userId, request);
    }

    @Delete("/{roomId}")
    @Status(HttpStatus.NO_CONTENT)
    void closeRoom(UUID roomId, @CurrentUser UUID userId) {
        rooms.closeRoom(roomId, userId);
    }
}
