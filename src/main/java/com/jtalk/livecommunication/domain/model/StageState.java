package com.jtalk.livecommunication.domain.model;

import java.util.List;
import java.util.UUID;

/** Snapshot of a room's stage: seated users (host included, seating order) and the hand-raise queue (FIFO). */
public record StageState(List<UUID> speakers, List<UUID> handRaiseQueue) {

    public StageState {
        speakers = List.copyOf(speakers);
        handRaiseQueue = List.copyOf(handRaiseQueue);
    }
}
