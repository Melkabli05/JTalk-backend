package com.jtalk.livecommunication.service.impl;

import com.jtalk.livecommunication.repository.RosterRepository;
import com.jtalk.livecommunication.service.VoiceRoomService;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.zalando.problem.ThrowableProblem;

import java.util.Optional;
import java.util.UUID;

/**
 * What happens to a room when its host's account is deleted. A room with someone else on its stage carries on: the person who has been
 * on the stage the longest becomes its host, the one most trusted with the microphone and the most likely to be there. A room with
 * nobody else on its stage has no one to run it and is closed, as if the host had ended it. The deleted host is then taken out of the
 * room like anyone else (see {@code ParticipantService.removeFromAllRooms}).
 */
@Singleton
@Slf4j
@RequiredArgsConstructor
class HostSuccession {

    private final VoiceRoomService rooms;
    private final RosterRepository roster;

    void retire(UUID hostId) {
        for (UUID roomId : rooms.activeRoomIdsHostedBy(hostId)) {
            try {
                Optional<UUID> heir = roster.read(roomId).speakers().stream().filter(speaker -> !speaker.equals(hostId)).findFirst();
                if (heir.isPresent()) {
                    rooms.handOverRoom(roomId, hostId, heir.get());
                } else {
                    rooms.closeRoom(roomId, hostId);
                }
            } catch (ThrowableProblem alreadyGone) {
                log.debug("Room {} of deleted host {} was closed meanwhile", roomId, hostId);
            }
        }
    }
}
