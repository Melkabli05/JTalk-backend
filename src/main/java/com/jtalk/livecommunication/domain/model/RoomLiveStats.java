package com.jtalk.livecommunication.domain.model;

import java.util.List;
import java.util.UUID;

/** Real-time numbers for one room, read from Redis: who is on stage, how many are present, how many hands are up. */
public record RoomLiveStats(List<UUID> speakers, long participantCount, long handRaiseCount) {

    public static final RoomLiveStats EMPTY = new RoomLiveStats(List.of(), 0, 0);
}
