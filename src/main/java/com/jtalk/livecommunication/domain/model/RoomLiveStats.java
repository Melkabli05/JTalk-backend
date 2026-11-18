package com.jtalk.livecommunication.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;
import java.util.UUID;

/** Real-time numbers for one room, read from Redis: who is on stage, how many are present, how many hands are up. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable
public record RoomLiveStats(List<UUID> speakers, long participantCount, long handRaiseCount) {

    public static final RoomLiveStats EMPTY = new RoomLiveStats(List.of(), 0, 0);
}
