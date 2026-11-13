package com.jtalk.livecommunication.domain.model;

import com.jtalk.livecommunication.domain.enums.RoomEventType;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.UUID;

/**
 * A small notification pushed to everyone in the room over LiveKit's data channel. It carries no state: clients react by
 * refetching what changed (stage, participants).
 *
 * @param targetUserId the user the event is about, if any
 */
@Serdeable
public record RoomRealtimeEvent(RoomEventType type, UUID roomId, @Nullable UUID targetUserId, Instant timestamp) {
}
