package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.domain.dto.request.CreateVoiceRoomRequest;
import com.jtalk.livecommunication.domain.dto.request.UpdateVoiceRoomRequest;
import com.jtalk.livecommunication.domain.dto.response.VoiceRoomResponse;
import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.model.RoomLiveStats;
import com.jtalk.livecommunication.domain.service.VoiceRoomService;
import com.jtalk.livecommunication.domain.mapper.ApiMapper;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.Sort;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Patch;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.validation.Validated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

import java.security.Principal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller("/api/v1/voice-rooms")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.VIRTUAL)
@Validated
@RequiredArgsConstructor
class VoiceRoomController {

    private static final Sort NEWEST_FIRST = Sort.of(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final VoiceRoomService rooms;
    private final ApiMapper mapper;

    @Post
    HttpResponse<VoiceRoomResponse> create(Principal principal,
                                           @Body @Valid CreateVoiceRoomRequest request) {
        VoiceRoom room = rooms.create(request.name(), principal.getName(), request.maxSpeakers());
        return HttpResponse.created(mapper.toResponse(room, RoomLiveStats.EMPTY), URI.create("/api/v1/voice-rooms/" + room.id()));
    }

    /**
     * Keyset pagination: the first page needs no parameters; for the next one pass the last room's {@code createdAt}
     * and {@code id} as {@code afterCreatedAt} and {@code afterId}. Micronaut binds the values, no cursor token needed.
     */
    @Get
    CursoredPage<VoiceRoomResponse> listActive(@QueryValue(defaultValue = "20") @Min(1) @Max(100) int size,
                                               @Nullable @QueryValue Instant afterCreatedAt,
                                               @Nullable @QueryValue UUID afterId) {
        CursoredPageable pageable = afterCreatedAt == null || afterId == null
                ? CursoredPageable.from(size, NEWEST_FIRST)
                : CursoredPageable.from(0, Pageable.Cursor.of(afterCreatedAt, afterId), Pageable.Mode.CURSOR_NEXT, size, NEWEST_FIRST, false);
        CursoredPage<VoiceRoom> page = rooms.listActive(pageable);
        Map<UUID, RoomLiveStats> live = rooms.liveStats(page.getContent().stream().map(VoiceRoom::id).toList());
        return page.map(room -> mapper.toResponse(room, live.get(room.id())));
    }

    @Get("/{roomId}")
    VoiceRoomResponse get(UUID roomId) {
        VoiceRoom room = rooms.get(roomId);
        return mapper.toResponse(room, rooms.liveStats(List.of(roomId)).get(roomId));
    }

    @Patch("/{roomId}")
    VoiceRoomResponse update(UUID roomId,
                             Principal principal,
                             @Body @Valid UpdateVoiceRoomRequest request) {
        VoiceRoom room = rooms.update(roomId, principal.getName(), request.name(), request.maxSpeakers());
        return mapper.toResponse(room, rooms.liveStats(List.of(roomId)).get(roomId));
    }

    @Delete("/{roomId}")
    HttpResponse<Void> close(UUID roomId, Principal principal) {
        rooms.close(roomId, principal.getName());
        return HttpResponse.noContent();
    }
}
