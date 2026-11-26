package com.jtalk.livecommunication.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jtalk.livecommunication.domain.enums.RoomFeedEventType;
import com.jtalk.livecommunication.domain.model.RoomLiveStats;
import com.jtalk.livecommunication.domain.model.RoomSpeakerSummary;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;
import java.util.UUID;

/**
 * One message of the room feed. Which fields are set depends on the type: {@code rooms} for {@code SNAPSHOT}, {@code room} for
 * {@code ROOM_OPENED} and {@code ROOM_UPDATED}, {@code live} for {@code ROOM_STATS}, and for the last three the {@code roomId}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Serdeable
public record RoomFeedEvent(RoomFeedEventType type, @Nullable UUID roomId, @Nullable VoiceRoomResponse room,
                            @Nullable List<VoiceRoomResponse> rooms, @Nullable RoomLiveStats live,
                            @Nullable List<RoomSpeakerSummary> speakerProfiles) {

    public static RoomFeedEvent snapshot(List<VoiceRoomResponse> rooms) {
        return new RoomFeedEvent(RoomFeedEventType.SNAPSHOT, null, null, rooms, null, null);
    }

    public static RoomFeedEvent opened(VoiceRoomResponse room) {
        return new RoomFeedEvent(RoomFeedEventType.ROOM_OPENED, room.id(), room, null, null, null);
    }

    public static RoomFeedEvent updated(VoiceRoomResponse room) {
        return new RoomFeedEvent(RoomFeedEventType.ROOM_UPDATED, room.id(), room, null, null, null);
    }

    public static RoomFeedEvent closed(UUID roomId) {
        return new RoomFeedEvent(RoomFeedEventType.ROOM_CLOSED, roomId, null, null, null, null);
    }

    public static RoomFeedEvent stats(UUID roomId, RoomLiveStats live) {
        return stats(roomId, live, null);
    }

    public static RoomFeedEvent stats(UUID roomId, RoomLiveStats live, @Nullable List<RoomSpeakerSummary> speakerProfiles) {
        return new RoomFeedEvent(RoomFeedEventType.ROOM_STATS, roomId, null, null, live, speakerProfiles);
    }
}
