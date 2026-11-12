package com.jtalk.livecommunication.domain.service.impl;

import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.enums.VoiceRoomStatus;
import com.jtalk.livecommunication.domain.event.VoiceRoomClosed;
import com.jtalk.livecommunication.domain.model.RoomLiveStats;
import com.jtalk.livecommunication.domain.repository.LiveStatsRepository;
import com.jtalk.livecommunication.domain.repository.VoiceRoomRepository;
import com.jtalk.livecommunication.domain.service.MediaService;
import com.jtalk.livecommunication.domain.service.VoiceRoomService;
import com.jtalk.livecommunication.exception.ProblemType;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.transaction.annotation.TransactionalEventListener;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Singleton
@Slf4j
@RequiredArgsConstructor
class VoiceRoomServiceImpl implements VoiceRoomService {

    private final VoiceRoomRepository rooms;
    private final LiveStatsRepository liveStats;
    private final MediaService media;
    private final ApplicationEventPublisher<VoiceRoomClosed> closedEvents;
    private final Clock clock;

    @Override
    public VoiceRoom create(String name, String hostId, Integer maxSpeakers) {
        return rooms.save(VoiceRoom.open(name, hostId, maxSpeakers));
    }

    @Override
    public VoiceRoom get(UUID roomId) {
        return rooms.findById(roomId).orElseThrow(() ->
                ProblemType.VOICE_ROOM_NOT_FOUND.because("Voice room " + roomId + " does not exist"));
    }

    @Override
    public VoiceRoom getActive(UUID roomId) {
        VoiceRoom room = get(roomId);
        if (!room.isActive()) {
            throw ProblemType.VOICE_ROOM_CLOSED.because("Voice room " + roomId + " is closed");
        }
        return room;
    }

    @Override
    public VoiceRoom getActiveAsHost(UUID roomId, String userId) {
        VoiceRoom room = getActive(roomId);
        if (!room.isHost(userId)) {
            throw ProblemType.NOT_ROOM_HOST.because("Only the room host can perform this action");
        }
        return room;
    }

    @Override
    public VoiceRoom update(UUID roomId, String requesterId, @Nullable String name, @Nullable Integer maxSpeakers) {
        VoiceRoom room = getActiveAsHost(roomId, requesterId);
        return rooms.update(room.withSettings(name, maxSpeakers));
    }

    @Override
    public Map<UUID, RoomLiveStats> liveStats(Collection<UUID> roomIds) {
        return liveStats.find(roomIds);
    }

    @Override
    public CursoredPage<VoiceRoom> listActive(CursoredPageable pageable) {
        return rooms.findByStatus(VoiceRoomStatus.ACTIVE, pageable);
    }

    /**
     * The transaction covers only the database write. Everything else (clearing Redis state, deleting the LiveKit
     * room) happens in {@code @TransactionalEventListener}s after the commit, so no network call ever holds a
     * database connection, and nothing runs if the write rolls back.
     */
    @Override
    @Transactional
    public void close(UUID roomId, String requesterId) {
        VoiceRoom room = getActiveAsHost(roomId, requesterId);
        rooms.update(room.close(clock.instant()));
        closedEvents.publishEvent(new VoiceRoomClosed(roomId));
    }

    @TransactionalEventListener
    void onRoomClosed(VoiceRoomClosed event) {
        try {
            media.closeRoom(event.roomId());
        } catch (RuntimeException e) {
            // The room is already closed in our source of truth; LiveKit empties idle rooms by itself.
            log.warn("Room {} closed but media server cleanup failed", event.roomId(), e);
        }
    }
}
