package com.jtalk.livecommunication.domain.enums;

/** What the room hallway is told, so it can keep its list of open rooms without asking for it again. */
public enum RoomFeedEventType {
    /** The first message of every stream: the open rooms as they are now. Everything after it is a change to this list. */
    SNAPSHOT,
    /** A room was opened. Carries the room. */
    ROOM_OPENED,
    /** The host changed the title, topic or seats. Carries the room. */
    ROOM_UPDATED,
    /** A room was closed. Carries only its id. */
    ROOM_CLOSED,
    /** Who is on stage, how many are present and how many hands are up changed. Carries the numbers. */
    ROOM_STATS
}
