package com.jtalk.livecommunication.domain.enums;

/** What a comment in a room is. Only these two come from people: the room itself announces everything else as an event. */
public enum CommentType {
    /** A plain message. */
    TEXT,
    /** A correction of what someone said: what they said ({@code content}) and how it should be ({@code correctedContent}). */
    CORRECTION
}
