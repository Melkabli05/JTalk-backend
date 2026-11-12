package com.jtalk.livecommunication.domain.service.impl;

import com.jtalk.livecommunication.config.VoiceRoomProperties;
import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.enums.ParticipantRole;
import com.jtalk.livecommunication.domain.enums.PresenceStatus;
import com.jtalk.livecommunication.domain.event.VoiceRoomClosed;
import com.jtalk.livecommunication.domain.model.Participant;
import com.jtalk.livecommunication.domain.repository.PresenceRepository;
import com.jtalk.livecommunication.domain.service.MediaService;
import com.jtalk.livecommunication.domain.service.MediaService.MediaConnection;
import com.jtalk.livecommunication.domain.service.ParticipantService;
import com.jtalk.livecommunication.domain.service.StageLeaseService;
import com.jtalk.livecommunication.domain.service.StageService;
import com.jtalk.livecommunication.domain.service.VoiceRoomService;
import com.jtalk.livecommunication.exception.ProblemType;
import io.micronaut.transaction.annotation.TransactionalEventListener;
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
    private final PresenceRepository presence;
    private final MediaService media;
    private final Clock clock;
    private final Duration presenceTimeout;

    ParticipantServiceImpl(VoiceRoomService rooms,
                           StageService stage,
                           StageLeaseService leases,
                           PresenceRepository presence,
                           MediaService media,
                           Clock clock,
                           VoiceRoomProperties properties) {
        this.rooms = rooms;
        this.stage = stage;
        this.leases = leases;
        this.presence = presence;
        this.media = media;
        this.clock = clock;
        this.presenceTimeout = properties.presenceTimeout();
    }

    @Override
    public Joined join(UUID roomId, String userId) {
        VoiceRoom room = rooms.getActive(roomId);
        if (presence.isBanned(roomId, userId)) {
            throw ProblemType.KICKED_FROM_ROOM.because(userId + " was removed from this room by the host");
        }
        ParticipantRole role = roleOf(room, userId, stage.isOnStage(roomId, userId));
        MediaConnection connection = media.issueConnection(roomId, userId, role != ParticipantRole.LISTENER);
        presence.touch(roomId, userId, clock.instant());
        leases.onReconnect(roomId, userId);
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
    }

    @Override
    public void kick(UUID roomId, String hostId, String targetUserId) {
        VoiceRoom room = rooms.getActiveAsHost(roomId, hostId);
        if (room.isHost(targetUserId)) {
            throw ProblemType.CANNOT_KICK_HOST.because("The host cannot be kicked from their own room");
        }
        presence.ban(roomId, targetUserId);
        if (stage.isOnStage(roomId, targetUserId)) {
            stage.releaseSeatAndPromoteNext(roomId, targetUserId);
        } else {
            stage.clearUser(roomId, targetUserId);
        }
        presence.remove(roomId, targetUserId);
        media.removeParticipant(roomId, targetUserId);
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

    @TransactionalEventListener
    void onRoomClosed(VoiceRoomClosed event) {
        presence.clear(event.roomId());
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
