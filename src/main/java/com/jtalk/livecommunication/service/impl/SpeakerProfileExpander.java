package com.jtalk.livecommunication.service.impl;

import com.jtalk.livecommunication.domain.model.RoomSpeakerSummary;
import com.jtalk.livecommunication.dto.response.VoiceRoomResponse;
import com.jtalk.livecommunication.repository.PresenceRepository;
import com.jtalk.livecommunication.repository.SpeakerProfileRepository;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Enriches room DTOs and feed events with cached host, speaker, and participant summaries in pipelined Redis round trips. */
@Singleton
@RequiredArgsConstructor
class SpeakerProfileExpander {

    private static final int PARTICIPANT_PREVIEW_LIMIT = 8;

    private final SpeakerProfileRepository speakerProfiles;
    private final PresenceRepository presence;

    VoiceRoomResponse expand(VoiceRoomResponse room) {
        return expandAll(List.of(room)).getFirst();
    }

    List<VoiceRoomResponse> expandAll(List<VoiceRoomResponse> rooms) {
        if (rooms.isEmpty()) {
            return rooms;
        }
        Map<UUID, List<UUID>> presentByRoom = presence.findPresentUsers(
                rooms.stream().map(VoiceRoomResponse::id).toList(),
                PARTICIPANT_PREVIEW_LIMIT);
        Map<UUID, RoomSpeakerSummary> summaries = loadFor(rooms, presentByRoom);
        return rooms.stream()
                .map(room -> attach(room, presentByRoom.getOrDefault(room.id(), List.of()), summaries))
                .toList();
    }

    List<RoomSpeakerSummary> summariesFor(List<UUID> speakerIds) {
        if (speakerIds == null || speakerIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, RoomSpeakerSummary> found = speakerProfiles.findSummaries(speakerIds);
        return speakerIds.stream().map(found::get).filter(Objects::nonNull).toList();
    }

    private Map<UUID, RoomSpeakerSummary> loadFor(List<VoiceRoomResponse> rooms, Map<UUID, List<UUID>> presentByRoom) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (VoiceRoomResponse room : rooms) {
            ids.add(room.hostId());
            if (room.speakers() != null) {
                ids.addAll(room.speakers());
            }
            ids.addAll(presentByRoom.getOrDefault(room.id(), List.of()));
        }
        return speakerProfiles.findSummaries(ids);
    }

    private static VoiceRoomResponse attach(VoiceRoomResponse room, List<UUID> presentIds,
                                            Map<UUID, RoomSpeakerSummary> summaries) {
        List<RoomSpeakerSummary> speakers = room.speakers() == null
                ? List.of()
                : room.speakers().stream().map(summaries::get).filter(Objects::nonNull).toList();
        Set<UUID> speakerSet = room.speakers() == null ? Set.of() : new HashSet<>(room.speakers());
        List<RoomSpeakerSummary> participants = presentIds.stream()
                .filter(id -> !id.equals(room.hostId()) && !speakerSet.contains(id))
                .map(summaries::get)
                .filter(Objects::nonNull)
                .toList();
        return room.withProfiles(summaries.get(room.hostId()), speakers, participants);
    }
}
