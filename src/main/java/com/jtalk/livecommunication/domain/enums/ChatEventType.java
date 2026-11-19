package com.jtalk.livecommunication.domain.enums;

/** What happens in a room's chat that everyone in the room needs to see at once. */
public enum ChatEventType {
    COMMENT_POSTED,
    COMMENT_DELETED,
    /** Someone chose, changed or took back their reaction to a comment. */
    COMMENT_REACTED,
    /** The host muted someone in the chat: they may keep listening and speaking, but cannot comment. */
    CHAT_MUTED,
    CHAT_UNMUTED,
    /** The host pinned a topic, or cleared it (no {@code topic} in the event). */
    TOPIC_CHANGED
}
