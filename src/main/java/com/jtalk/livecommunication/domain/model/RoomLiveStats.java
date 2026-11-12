package com.jtalk.livecommunication.domain.model;

import java.util.List;

/** Real-time numbers for one room, read from Redis: who is on stage, how many are present, how many hands are up. */
public record RoomLiveStats(List<String> speakers, long participantCount, long handRaiseCount) {

    public static final RoomLiveStats EMPTY = new RoomLiveStats(List.of(), 0, 0);
}
