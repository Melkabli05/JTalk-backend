package com.jtalk.livecommunication.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jtalk.livecommunication.domain.enums.RoomFeedEventType;
import com.jtalk.livecommunication.domain.model.RoomLiveStats;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.UUID;

/**
 * One message of the room feed (server-sent events). Which fields are set depends on the type: {@code room} for
 * {@code ROOM_OPENED} and {@code ROOM_UPDATED}, {@code live} for {@code ROOM_STATS}, only {@code roomId} for
 * {@code ROOM_CLOSED}, nothing for {@code READY}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Serdeable
public record RoomFeedEvent(RoomFeedEventType type, @Nullable UUID roomId, @Nullable VoiceRoomResponse room, @Nullable RoomLiveStats live) {

    public static final RoomFeedEvent READY = new RoomFeedEvent(RoomFeedEventType.READY, null, null, null);

    public static RoomFeedEvent opened(VoiceRoomResponse room) {
        return new RoomFeedEvent(RoomFeedEventType.ROOM_OPENED, room.id(), room, null);
    }

    public static RoomFeedEvent updated(VoiceRoomResponse room) {
        return new RoomFeedEvent(RoomFeedEventType.ROOM_UPDATED, room.id(), room, null);
    }

    public static RoomFeedEvent closed(UUID roomId) {
        return new RoomFeedEvent(RoomFeedEventType.ROOM_CLOSED, roomId, null, null);
    }

    public static RoomFeedEvent stats(UUID roomId, RoomLiveStats live) {
        return new RoomFeedEvent(RoomFeedEventType.ROOM_STATS, roomId, null, live);
    }
}
