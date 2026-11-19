package com.jtalk.livecommunication.domain.enums;

/** What happens in a room's chat that everyone in the room needs to see at once. */
public enum ChatEventType {
    COMMENT_POSTED,
    COMMENT_DELETED,
    /** The host muted someone in the chat: they may keep listening and speaking, but cannot comment. */
    CHAT_MUTED,
    CHAT_UNMUTED,
    /** The host pinned a topic, or cleared it (no {@code topic} in the event). */
    TOPIC_CHANGED
}
