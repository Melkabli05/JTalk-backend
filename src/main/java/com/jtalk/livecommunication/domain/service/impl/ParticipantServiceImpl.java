package com.jtalk.livecommunication.domain.service.impl;

import com.jtalk.livecommunication.config.VoiceRoomProperties;
import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.enums.ParticipantRole;
import com.jtalk.livecommunication.domain.enums.PresenceStatus;
import com.jtalk.livecommunication.domain.event.VoiceRoomClosed;
import com.jtalk.livecommunication.domain.model.Participant;
import com.jtalk.livecommunication.domain.repository.PresenceRepository;
import com.jtalk.livecommunication.domain.service.AudienceBroadcastService;
import com.jtalk.livecommunication.domain.service.MediaService;
import com.jtalk.livecommunication.domain.service.MediaService.MediaConnection;
import com.jtalk.livecommunication.domain.service.ParticipantService;
import com.jtalk.livecommunication.domain.service.StageLeaseService;
import com.jtalk.livecommunication.domain.service.StageService;
import com.jtalk.livecommunication.domain.service.VoiceRoomService;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Singleton
class ParticipantServiceImpl implements ParticipantService {

    private final VoiceRoomService rooms;
    private final StageService stage;
    private final StageLeaseService leases;
    private final AudienceBroadcastService audience;
    private final PresenceRepository presence;
    private final MediaService media;
    private final Clock clock;
    private final Duration presenceTimeout;

    ParticipantServiceImpl(VoiceRoomService rooms,
                           StageService stage,
                           StageLeaseService leases,
                           AudienceBroadcastService audience,
                           PresenceRepository presence,
                           MediaService media,
                           Clock clock,
                           VoiceRoomProperties properties) {
        this.rooms = rooms;
        this.stage = stage;
        this.leases = leases;
        this.audience = audience;
        this.presence = presence;
        this.media = media;
        this.clock = clock;
        this.presenceTimeout = properties.presenceTimeout();
    }

    @Override
    public Joined join(UUID roomId, String userId) {
        VoiceRoom room = rooms.getActive(roomId);
        ParticipantRole role = roleOf(room, userId, stage.isOnStage(roomId, userId));
        MediaConnection connection = media.issueConnection(roomId, userId, role != ParticipantRole.LISTENER);
        presence.touch(roomId, userId, clock.instant());
        leases.onReconnect(roomId, userId);
        audience.recheck(roomId, presentCount(roomId));
        return new Joined(connection, role);
    }

    @Override
    public void heartbeat(UUID roomId, String userId) {
        rooms.getActive(roomId);
        presence.touch(roomId, userId, clock.instant());
    }

    @Override
    public void leave(UUID roomId, String userId) {
        presence.remove(roomId, userId);
        stage.clearUser(roomId, userId);
        audience.recheck(roomId, presentCount(roomId));
    }

    @Override
    public boolean isPresent(UUID roomId, String userId) {
        return presence.isPresent(roomId, userId, presenceCutoff());
    }

    @Override
    public List<Participant> listPresent(UUID roomId) {
        VoiceRoom room = rooms.get(roomId);
        var speakers = new HashSet<>(stage.getStage(roomId).speakers());
        List<String> present = presence.findPresent(roomId, presenceCutoff());
        Set<String> reconnecting = leases.reconnecting(roomId, present);
        return present.stream()
                .map(userId -> new Participant(userId,
                        roleOf(room, userId, speakers.contains(userId)),
                        reconnecting.contains(userId) ? PresenceStatus.RECONNECTING : PresenceStatus.ACTIVE))
                .toList();
    }

    @EventListener
    void onRoomClosed(VoiceRoomClosed event) {
        presence.clear(event.roomId());
    }

    private long presentCount(UUID roomId) {
        return presence.count(roomId, presenceCutoff());
    }

    private Instant presenceCutoff() {
        return clock.instant().minus(presenceTimeout);
    }

    private static ParticipantRole roleOf(VoiceRoom room, String userId, boolean onStage) {
        if (room.isHost(userId)) {
            return ParticipantRole.HOST;
        }
        return onStage ? ParticipantRole.SPEAKER : ParticipantRole.LISTENER;
    }
}
