package com.jtalk.livecommunication.domain.enums;

/** Things that happen in a room that every client's UI needs to know about right away. */
public enum RoomEventType {
    /** Seats changed (someone took, left or lost a seat, or was promoted). Carries the new stage. */
    STAGE_UPDATED,
    HAND_RAISED,
    HAND_LOWERED,
    /** A speaker's media dropped; their seat is held for the grace lease. */
    SPEAKER_RECONNECTING,
    SPEAKER_RECONNECTED,
    PRESENCE_CHANGED,
    /** Reactions people sent in the last moment, batched into one message. */
    REACTIONS
}
