package com.jtalk.livecommunication.service.impl;

import com.jtalk.livecommunication.domain.entity.VoiceRoom;
import com.jtalk.livecommunication.domain.enums.RoomEventType;
import com.jtalk.livecommunication.domain.enums.VoiceRoomStatus;
import com.jtalk.livecommunication.domain.event.HostLeaseExpired;
import com.jtalk.livecommunication.domain.event.IdleWarningExpired;
import com.jtalk.livecommunication.domain.event.SoloRoomExpired;
import com.jtalk.livecommunication.integration.LiveKitGateway;
import com.jtalk.livecommunication.repository.LeaseRepository;
import com.jtalk.livecommunication.repository.PresenceRepository;
import com.jtalk.livecommunication.repository.VoiceRoomRepository;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Handles background room lifecycle safeguards: host grace-lease expiry (succession or closure),
 * solo-room inactivity countdowns, and periodic LiveKit reconciliation for missed webhooks.
 */
@Singleton
@Slf4j
@RequiredArgsConstructor
class RoomLifecycleGuardian {

    private static final Duration RECONCILE_MIN_AGE = Duration.ofMinutes(2);

    private final VoiceRoomServiceImpl voiceRooms;
    private final VoiceRoomRepository rooms;
    private final PresenceRepository presence;
    private final LeaseRepository leases;
    private final LiveKitGateway gateway;
    private final RoomEvents events;
    private final Clock clock;

    @EventListener
    void onHostLeaseExpired(HostLeaseExpired event) {
        voiceRooms.promoteNextSpeakerOrClose(event.roomId());
    }

    @EventListener
    void onSoloRoomExpired(SoloRoomExpired event) {
        voiceRooms.findActiveRoom(event.roomId()).ifPresent(room -> {
            if (presence.count(room.id()) <= 1 && leases.createIdleWarning(room.id())) {
                log.info("Solo room {} idle; sending 60s warning to host {}", room.id(), room.hostId());
                events.announce(room.id(), RoomEventType.ROOM_IDLE_WARNING, room.hostId());
            }
        });
    }

    @EventListener
    void onIdleWarningExpired(IdleWarningExpired event) {
        voiceRooms.findActiveRoom(event.roomId()).ifPresent(room -> {
            if (presence.count(room.id()) <= 1) {
                log.info("Solo room {} idle warning expired; closing room", room.id());
                voiceRooms.closeRoomIfActive(room.id());
            }
        });
    }

    @Scheduled(fixedDelay = "10m", initialDelay = "2m")
    void reconcileAbandonedRooms() {
        List<VoiceRoom> activeRooms = rooms.findByStatus(VoiceRoomStatus.ACTIVE);
        if (activeRooms.isEmpty()) {
            return;
        }
        Set<UUID> liveRooms;
        try {
            liveRooms = gateway.listActiveRoomIds();
        } catch (RuntimeException e) {
            log.warn("Skipping room reconciliation sweep: could not list LiveKit rooms", e);
            return;
        }
        Instant cutoff = clock.instant().minus(RECONCILE_MIN_AGE);
        for (VoiceRoom room : activeRooms) {
            Instant created = room.createdAt();
            if (created != null && created.isBefore(cutoff) && !liveRooms.contains(room.id())) {
                log.info("Reconciliation sweep closing orphaned room {} missing from LiveKit", room.id());
                voiceRooms.closeRoomIfActive(room.id());
            }
        }
    }
}
