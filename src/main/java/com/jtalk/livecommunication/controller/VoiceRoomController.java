package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.domain.dto.request.CreateVoiceRoomRequest;
import com.jtalk.livecommunication.domain.dto.response.VoiceRoomResponse;
import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.service.VoiceRoomService;
import com.jtalk.livecommunication.security.RequestHeaders;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import io.micronaut.validation.Validated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.net.URI;
import java.util.List;
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
    HttpResponse<VoiceRoomResponse> create(@Header(RequestHeaders.USER_ID) @NotBlank String userId,
                                           @Body @Valid CreateVoiceRoomRequest request) {
        VoiceRoom room = rooms.create(request.name(), userId, request.maxSpeakers());
        return HttpResponse.created(VoiceRoomResponse.from(room), URI.create("/api/v1/voice-rooms/" + room.id()));
    }

    /** Pagination comes from Micronaut Data's built-in binding: {@code ?page=0&size=20}. */
    @Get
    List<VoiceRoomResponse> listActive(Pageable pageable) {
        return rooms.listActive(pageable).stream().map(VoiceRoomResponse::from).toList();
    }

    @Get("/{roomId}")
    VoiceRoomResponse get(UUID roomId) {
        return VoiceRoomResponse.from(rooms.get(roomId));
    }

    @Delete("/{roomId}")
    HttpResponse<Void> close(UUID roomId, @Header(RequestHeaders.USER_ID) @NotBlank String userId) {
        rooms.close(roomId, userId);
        return HttpResponse.noContent();
    }
}
