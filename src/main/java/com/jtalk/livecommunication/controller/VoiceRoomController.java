package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.domain.dto.request.CreateVoiceRoomRequest;
import com.jtalk.livecommunication.domain.dto.request.UpdateVoiceRoomRequest;
import com.jtalk.livecommunication.domain.dto.response.RoomSessionResponse;
import com.jtalk.livecommunication.domain.dto.response.VoiceRoomResponse;
import com.jtalk.livecommunication.domain.service.VoiceRoomService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.Sort;
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

import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

@Controller("/api/v1/voice-rooms")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.VIRTUAL)
@Validated
@RequiredArgsConstructor
class VoiceRoomController {

    private static final Sort NEWEST_FIRST = Sort.of(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final VoiceRoomService rooms;

    @Post
    HttpResponse<RoomSessionResponse> createRoom(Principal principal, @Body @Valid CreateVoiceRoomRequest request) {
        RoomSessionResponse session = rooms.createRoom(UUID.fromString(principal.getName()), request);
        return HttpResponse.created(session, URI.create("/api/v1/voice-rooms/" + session.room().id()));
    }

    /**
     * Keyset pagination: the first page needs no cursor; for the next one pass the last room's {@code createdAt} and
     * {@code id} as {@code afterCreatedAt} and {@code afterId}. Micronaut binds the values, no cursor token needed.
     */
    @Get
    CursoredPage<VoiceRoomResponse> listActiveRooms(@Nullable @QueryValue String targetLanguage,
                                                    @QueryValue(defaultValue = "20") @Min(1) @Max(100) int size,
                                                    @Nullable @QueryValue Instant afterCreatedAt,
                                                    @Nullable @QueryValue UUID afterId) {
        CursoredPageable pageable = afterCreatedAt == null || afterId == null
                ? CursoredPageable.from(size, NEWEST_FIRST)
                : CursoredPageable.from(0, Pageable.Cursor.of(afterCreatedAt, afterId), Pageable.Mode.CURSOR_NEXT, size, NEWEST_FIRST, false);
        return rooms.listActiveRooms(targetLanguage, pageable);
    }

    @Get("/{roomId}")
    VoiceRoomResponse getRoom(UUID roomId) {
        return rooms.getRoom(roomId);
    }

    @Patch("/{roomId}")
    VoiceRoomResponse updateRoom(UUID roomId, Principal principal, @Body @Valid UpdateVoiceRoomRequest request) {
        return rooms.updateRoom(roomId, UUID.fromString(principal.getName()), request);
    }

    @Delete("/{roomId}")
    @Status(HttpStatus.NO_CONTENT)
    void closeRoom(UUID roomId, Principal principal) {
        rooms.closeRoom(roomId, UUID.fromString(principal.getName()));
    }
}
