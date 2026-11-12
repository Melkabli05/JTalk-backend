package com.jtalk.livecommunication.domain.enums;

public enum PresenceStatus {
    ACTIVE,
    /** Disconnected from the media server but still holding a stage seat under a grace lease. */
    RECONNECTING
}
