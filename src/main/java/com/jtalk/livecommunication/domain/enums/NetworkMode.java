package com.jtalk.livecommunication.domain.enums;

public enum NetworkMode {
    OPTIMAL,
    /** Prune secondary audio tracks so a struggling connection only downloads the ones it needs. */
    LOW_BANDWIDTH
}
