package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.domain.dto.request.CreateVoiceRoomRequest;
import com.jtalk.livecommunication.domain.dto.request.UpdateVoiceRoomRequest;
import com.jtalk.livecommunication.domain.dto.response.VoiceRoomResponse;
import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.model.RoomLiveStats;
import com.jtalk.livecommunication.domain.service.VoiceRoomService;
import com.jtalk.livecommunication.security.CallerId;
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
import io.micronaut.validation.Validated;
import jakarta.validation.Valid;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller("/api/v1/voice-rooms")
@ExecuteOn(TaskExecutors.VIRTUAL)
@Validated
class VoiceRoomController {

    private final VoiceRoomService rooms;

    VoiceRoomController(VoiceRoomService rooms) {
        this.rooms = rooms;
    }

    @Post
    HttpResponse<VoiceRoomResponse> create(@CallerId String userId,
                                           @Body @Valid CreateVoiceRoomRequest request) {
        VoiceRoom room = rooms.create(request.name(), userId, request.maxSpeakers());
        return HttpResponse.created(VoiceRoomResponse.from(room, RoomLiveStats.EMPTY), URI.create("/api/v1/voice-rooms/" + room.id()));
    }

    /** Pagination comes from Micronaut Data's built-in binding: {@code ?page=0&size=20}. */
    @Get
    List<VoiceRoomResponse> listActive(Pageable pageable) {
        List<VoiceRoom> page = rooms.listActive(pageable);
        Map<UUID, RoomLiveStats> live = rooms.liveStats(page.stream().map(VoiceRoom::id).toList());
        return page.stream().map(room -> VoiceRoomResponse.from(room, live.get(room.id()))).toList();
    }

    @Get("/{roomId}")
    VoiceRoomResponse get(UUID roomId) {
        VoiceRoom room = rooms.get(roomId);
        return VoiceRoomResponse.from(room, rooms.liveStats(List.of(roomId)).get(roomId));
    }

    @Patch("/{roomId}")
    VoiceRoomResponse update(UUID roomId,
                             @CallerId String userId,
                             @Body @Valid UpdateVoiceRoomRequest request) {
        VoiceRoom room = rooms.update(roomId, userId, request.name(), request.maxSpeakers());
        return VoiceRoomResponse.from(room, rooms.liveStats(List.of(roomId)).get(roomId));
    }

    @Delete("/{roomId}")
    HttpResponse<Void> close(UUID roomId, @CallerId String userId) {
        rooms.close(roomId, userId);
        return HttpResponse.noContent();
    }
}
