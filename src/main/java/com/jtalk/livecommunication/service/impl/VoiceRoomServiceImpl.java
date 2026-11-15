package com.jtalk.livecommunication.service.impl;

import com.jtalk.livecommunication.dto.request.CreateVoiceRoomRequest;
import com.jtalk.livecommunication.dto.request.UpdateVoiceRoomRequest;
import com.jtalk.livecommunication.dto.response.RoomSessionResponse;
import com.jtalk.livecommunication.dto.response.VoiceRoomResponse;
import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.enums.ParticipantRole;
import com.jtalk.livecommunication.domain.enums.VoiceRoomStatus;
import com.jtalk.livecommunication.domain.event.VoiceRoomClosed;
import com.jtalk.livecommunication.dto.ApiMapper;
import com.jtalk.livecommunication.domain.model.RoomLiveStats;
import com.jtalk.livecommunication.repository.LiveStatsRepository;
import com.jtalk.livecommunication.repository.StageRepository;
import com.jtalk.livecommunication.repository.VoiceRoomRepository;
import com.jtalk.livecommunication.service.VoiceRoomService;
import com.jtalk.livecommunication.service.RoomAccessValidator;
import com.jtalk.livecommunication.config.ProblemType;
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
import java.util.Optional;
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
    private final RoomAccessValidator access;

    @Override
    public RoomSessionResponse createRoom(UUID hostId, CreateVoiceRoomRequest request) {
        return rooms.findFirstByHostIdAndStatus(hostId, VoiceRoomStatus.ACTIVE)
                .map(existing -> rejoin(existing, hostId))   // a host runs one room at a time
                .orElseGet(() -> open(hostId, request));
    }

    private RoomSessionResponse rejoin(VoiceRoom room, UUID hostId) {
        log.info("Host {} already has active room {}, re-joining it", hostId, room.id());
        return new RoomSessionResponse(toResponse(room), gateway.serverUrl(), gateway.generateToken(room.id(), hostId, true),
                ParticipantRole.HOST, true);
    }

    private RoomSessionResponse open(UUID hostId, CreateVoiceRoomRequest request) {
        VoiceRoom saved = rooms.save(VoiceRoom.open(request.title(), request.topic(), hostId, request.targetLanguage(),
                request.nativeLanguage(), request.seatsOrDefault()));
        try {
            gateway.createRoom(saved.id());
            stage.initializeStage(saved.id(), hostId);
        } catch (RuntimeException e) {
            rooms.deleteById(saved.id());   // never leave an open room whose media room or stage does not exist
            throw e;
        }
        String token = gateway.generateToken(saved.id(), hostId, true);
        log.info("Created voice room {} hosted by {}", saved.id(), hostId);

        return new RoomSessionResponse(toResponse(saved), gateway.serverUrl(), token, ParticipantRole.HOST, true);
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
        access.requireHost(room, requesterId, "update room settings");
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
        access.requireHost(room, requesterId, "close this room");
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
    public Optional<VoiceRoom> findActiveRoom(UUID roomId) {
        return rooms.findByIdAndStatus(roomId, VoiceRoomStatus.ACTIVE);
    }

    @Override
    public VoiceRoom getRequiredActiveRoom(UUID roomId) {
        return findActiveRoom(roomId)
                .orElseThrow(() -> ProblemType.VOICE_ROOM_NOT_FOUND.because("Voice room " + roomId + " does not exist or is closed"));
    }

    @Override
    public VoiceRoomResponse toResponse(VoiceRoom room) {
        return mapper.toResponse(room, liveStats.find(List.of(room.id())).get(room.id()));
    }
}
