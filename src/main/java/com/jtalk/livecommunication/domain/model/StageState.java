package com.jtalk.livecommunication.domain.model;

import java.util.List;

/** Snapshot of a room's stage: approved speakers (seating order) and the hand-raise queue (FIFO). */
public record StageState(List<String> speakers, List<String> handRaiseQueue) {

    public StageState {
        speakers = List.copyOf(speakers);
        handRaiseQueue = List.copyOf(handRaiseQueue);
    }
}
