package com.jtalk.livecommunication.service.impl;

import com.jtalk.livecommunication.domain.model.RoomSpeakerSummary;
import com.jtalk.livecommunication.dto.response.VoiceRoomResponse;
import com.jtalk.livecommunication.repository.SpeakerProfileRepository;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Enriches room DTOs and feed events with cached host and speaker summaries in a single Redis round trip. */
@Singleton
@RequiredArgsConstructor
class SpeakerProfileExpander {

    private final SpeakerProfileRepository speakerProfiles;

    VoiceRoomResponse expand(VoiceRoomResponse room) {
        return attach(room, loadFor(List.of(room)));
    }

    List<VoiceRoomResponse> expandAll(List<VoiceRoomResponse> rooms) {
        if (rooms.isEmpty()) {
            return rooms;
        }
        Map<UUID, RoomSpeakerSummary> summaries = loadFor(rooms);
        return rooms.stream().map(room -> attach(room, summaries)).toList();
    }

    List<RoomSpeakerSummary> summariesFor(List<UUID> speakerIds) {
        if (speakerIds == null || speakerIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, RoomSpeakerSummary> found = speakerProfiles.findSummaries(speakerIds);
        return speakerIds.stream().map(found::get).filter(Objects::nonNull).toList();
    }

    private Map<UUID, RoomSpeakerSummary> loadFor(List<VoiceRoomResponse> rooms) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (VoiceRoomResponse room : rooms) {
            ids.add(room.hostId());
            if (room.speakers() != null) {
                ids.addAll(room.speakers());
            }
        }
        return speakerProfiles.findSummaries(ids);
    }

    private static VoiceRoomResponse attach(VoiceRoomResponse room, Map<UUID, RoomSpeakerSummary> summaries) {
        List<RoomSpeakerSummary> speakers = room.speakers() == null
                ? List.of()
                : room.speakers().stream().map(summaries::get).filter(Objects::nonNull).toList();
        return room.withProfiles(summaries.get(room.hostId()), speakers);
    }
}
