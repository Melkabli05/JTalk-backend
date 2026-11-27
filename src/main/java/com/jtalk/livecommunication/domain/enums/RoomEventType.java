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
    /** The host changed the title, topic or seats. Carries the new settings. */
    ROOM_UPDATED,
    /** Reactions people sent in the last moment, batched into one message. */
    REACTIONS,
    /** A participant updated their profile (picture). Carries the participant profile update. */
    PARTICIPANT_PROFILE_UPDATED,
    /** A comment was pinned. Carries the comment ID. */
    COMMENT_PINNED,
    /** A comment was unpinned. Carries the comment ID. */
    COMMENT_UNPINNED,
    /** The host's media dropped; a grace lease is active before succession or closure. */
    HOST_RECONNECTING,
    /** The host reconnected before the grace lease expired. */
    HOST_RECONNECTED,
    /** Host role was automatically transferred to the longest-seated speaker. */
    HOST_TRANSFERRED,
    /** Solo room inactivity warning: host must tap Stay Live before the countdown expires. */
    ROOM_IDLE_WARNING
}
