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
import io.micronaut.data.model.Pageable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Singleton
class VoiceRoomServiceImpl implements VoiceRoomService {

    private static final Logger LOG = LoggerFactory.getLogger(VoiceRoomServiceImpl.class);

    private final VoiceRoomRepository rooms;
    private final LiveStatsRepository liveStats;
    private final MediaService media;
    private final ApplicationEventPublisher<VoiceRoomClosed> closedEvents;
    private final Clock clock;

    VoiceRoomServiceImpl(VoiceRoomRepository rooms,
                         LiveStatsRepository liveStats,
                         MediaService media,
                         ApplicationEventPublisher<VoiceRoomClosed> closedEvents,
                         Clock clock) {
        this.liveStats = liveStats;
        this.rooms = rooms;
        this.media = media;
        this.closedEvents = closedEvents;
        this.clock = clock;
    }

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
    public List<VoiceRoom> listActive(Pageable pageable) {
        return rooms.findByStatusOrderByCreatedAtDesc(VoiceRoomStatus.ACTIVE, pageable);
    }

    @Override
    public void close(UUID roomId, String requesterId) {
        VoiceRoom room = getActiveAsHost(roomId, requesterId);
        rooms.update(room.close(clock.instant()));
        closedEvents.publishEvent(new VoiceRoomClosed(roomId));
        try {
            media.closeRoom(roomId);
        } catch (RuntimeException e) {
            // The room is already closed in our source of truth; LiveKit empties idle rooms by itself.
            LOG.warn("Room {} closed but media server cleanup failed", roomId, e);
        }
    }
}
