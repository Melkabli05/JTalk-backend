package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.domain.dto.request.CreateVoiceRoomRequest;
import com.jtalk.livecommunication.domain.dto.request.UpdateVoiceRoomRequest;
import com.jtalk.livecommunication.domain.dto.response.VoiceRoomResponse;
import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.model.RoomLiveStats;
import com.jtalk.livecommunication.domain.service.VoiceRoomService;
import com.jtalk.livecommunication.domain.mapper.ApiMapper;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
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
import lombok.RequiredArgsConstructor;

import java.security.Principal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller("/api/v1/voice-rooms")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.VIRTUAL)
@Validated
@RequiredArgsConstructor
class VoiceRoomController {

    private final VoiceRoomService rooms;
    private final ApiMapper mapper;

    @Post
    HttpResponse<VoiceRoomResponse> create(Principal principal,
                                           @Body @Valid CreateVoiceRoomRequest request) {
        VoiceRoom room = rooms.create(request.name(), principal.getName(), request.maxSpeakers());
        return HttpResponse.created(mapper.toResponse(room, RoomLiveStats.EMPTY), URI.create("/api/v1/voice-rooms/" + room.id()));
    }

    /** Pagination comes from Micronaut Data's built-in binding: {@code ?page=0&size=20}. */
    @Get
    List<VoiceRoomResponse> listActive(Pageable pageable) {
        List<VoiceRoom> page = rooms.listActive(pageable);
        Map<UUID, RoomLiveStats> live = rooms.liveStats(page.stream().map(VoiceRoom::id).toList());
        return page.stream().map(room -> mapper.toResponse(room, live.get(room.id()))).toList();
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
