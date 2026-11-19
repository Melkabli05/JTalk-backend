package com.jtalk.livecommunication.domain.model;

import com.jtalk.livecommunication.domain.enums.RoomEventType;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A small update pushed to everyone in the room over LiveKit's data channel. It carries the state it changed, so clients apply
 * it in memory instead of refetching.
 *
 * @param targetUserId        the user the event is about, if any
 * @param stage               the stage after the change, for events that change it
 * @param presence            who came and went, for {@code PRESENCE_CHANGED}
 * @param reactions           what people reacted with, for {@code REACTIONS}
 * @param settings            the room's title, topic and seats, for {@code ROOM_UPDATED}
 * @param profileUpdate       the participant's updated profile, for {@code PARTICIPANT_PROFILE_UPDATED}
 * @param commentId           the comment ID for {@code COMMENT_PINNED} and {@code COMMENT_UNPINNED} events
 */
@Serdeable
public record RoomRealtimeEvent(RoomEventType type, UUID roomId, @Nullable UUID targetUserId, Instant timestamp,
                                @Nullable StageState stage, @Nullable PresenceChange presence,
                                @Nullable List<ReactionBurst> reactions, @Nullable RoomSettings settings,
                                @Nullable ParticipantProfileUpdate profileUpdate, @Nullable String commentId) {
}
