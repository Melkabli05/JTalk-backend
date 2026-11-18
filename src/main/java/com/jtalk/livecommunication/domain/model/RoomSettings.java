package com.jtalk.livecommunication.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.UUID;

/** What can change about a room (the host edits it, or hands it over), as the people in it are told: who hosts it now, too. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable
public record RoomSettings(String title, @Nullable String topic, int maxStageSeats, UUID hostId) {
}
