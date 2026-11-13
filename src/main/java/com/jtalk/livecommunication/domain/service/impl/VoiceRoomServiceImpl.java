package com.jtalk.livecommunication.domain.service.impl;

import com.jtalk.livecommunication.config.CommunicationConstants;
import com.jtalk.livecommunication.domain.dto.request.CreateVoiceRoomRequest;
import com.jtalk.livecommunication.domain.dto.request.UpdateVoiceRoomRequest;
import com.jtalk.livecommunication.domain.dto.response.RoomSessionResponse;
import com.jtalk.livecommunication.domain.dto.response.VoiceRoomResponse;
import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.enums.ParticipantRole;
import com.jtalk.livecommunication.domain.enums.VoiceRoomStatus;
import com.jtalk.livecommunication.domain.event.VoiceRoomClosed;
import com.jtalk.livecommunication.domain.mapper.ApiMapper;
import com.jtalk.livecommunication.domain.model.RoomLiveStats;
import com.jtalk.livecommunication.domain.repository.LiveStatsRepository;
import com.jtalk.livecommunication.domain.repository.StageRepository;
import com.jtalk.livecommunication.domain.repository.VoiceRoomRepository;
import com.jtalk.livecommunication.domain.service.VoiceRoomService;
import com.jtalk.livecommunication.exception.ProblemType;
import com.jtalk.livecommunication.integration.LiveKitGateway;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.transaction.annotation.Transactional;
import io.micronaut.transaction.annotation.TransactionalEventListener;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Singleton
@Slf4j
@RequiredArgsConstructor
class VoiceRoomServiceImpl implements VoiceRoomService {

    private final VoiceRoomRepository rooms;
    private final StageRepository stage;
    private final LiveStatsRepository liveStats;
    private final LiveKitGateway gateway;
    private final ApplicationEventPublisher<VoiceRoomClosed> closedEvents;
    private final ApiMapper mapper;

    @Override
    public RoomSessionResponse createRoom(UUID hostId, CreateVoiceRoomRequest request) {
        VoiceRoom saved = rooms.save(VoiceRoom.open(
                request.title(), request.topic(), hostId, request.targetLanguage(), request.nativeLanguage(),
                request.maxStageSeats() == null ? CommunicationConstants.DEFAULT_STAGE_SEATS : request.maxStageSeats(),
                request.allowWhisper() == null || request.allowWhisper()));

        gateway.createRoom(saved.id());
        stage.initializeStage(saved.id(), hostId);
        String token = gateway.generateToken(saved.id(), hostId, true);
        log.info("Created voice room {} hosted by {}", saved.id(), hostId);

        return new RoomSessionResponse(toResponse(saved), token, ParticipantRole.HOST, true);
    }

    @Override
    public CursoredPage<VoiceRoomResponse> listActiveRooms(@Nullable String targetLanguage, CursoredPageable pageable) {
        CursoredPage<VoiceRoom> page = targetLanguage == null || targetLanguage.isBlank()
                ? rooms.findByStatus(VoiceRoomStatus.ACTIVE, pageable)
                : rooms.findByStatusAndTargetLanguage(VoiceRoomStatus.ACTIVE, targetLanguage, pageable);
        Map<UUID, RoomLiveStats> live = liveStats.find(page.getContent().stream().map(VoiceRoom::id).toList());
        return page.map(room -> mapper.toResponse(room, live.get(room.id())));
    }

    @Override
    public VoiceRoomResponse getRoom(UUID roomId) {
        return toResponse(getRequiredActiveRoom(roomId));
    }

    @Override
    public VoiceRoomResponse updateRoom(UUID roomId, UUID requesterId, UpdateVoiceRoomRequest request) {
        VoiceRoom room = getRequiredActiveRoom(roomId);
        if (!room.isHost(requesterId)) {
            throw ProblemType.NOT_ROOM_HOST.because("Only the room host can update room settings");
        }
        return toResponse(rooms.update(room.withSettings(request.title(), request.topic(), request.maxStageSeats())));
    }

    /**
     * The transaction covers only the database write. Everything else (clearing Redis state, deleting the LiveKit room)
     * happens in {@code @TransactionalEventListener}s after the commit, so no network call ever holds a database
     * connection, and nothing runs if the write rolls back.
     */
    @Override
    @Transactional
    public void closeRoom(UUID roomId, UUID requesterId) {
        VoiceRoom room = getRequiredActiveRoom(roomId);
        if (!room.isHost(requesterId)) {
            throw ProblemType.NOT_ROOM_HOST.because("Only the room host can close this room");
        }
        rooms.update(room.close());
        closedEvents.publishEvent(new VoiceRoomClosed(roomId));
        log.info("Closed voice room {} by host {}", roomId, requesterId);
    }

    @TransactionalEventListener
    void onRoomClosed(VoiceRoomClosed event) {
        try {
            gateway.deleteRoom(event.roomId());
        } catch (RuntimeException e) {
            // The room is already closed in our source of truth; LiveKit empties idle rooms by itself.
            log.warn("Room {} closed but media server cleanup failed", event.roomId(), e);
        }
    }

    @Override
    public VoiceRoom getRequiredActiveRoom(UUID roomId) {
        return rooms.findByIdAndStatus(roomId, VoiceRoomStatus.ACTIVE)
                .orElseThrow(() -> ProblemType.VOICE_ROOM_NOT_FOUND.because("Voice room " + roomId + " does not exist or is closed"));
    }

    @Override
    public VoiceRoomResponse toResponse(VoiceRoom room) {
        return mapper.toResponse(room, liveStats.find(List.of(room.id())).get(room.id()));
    }
}
