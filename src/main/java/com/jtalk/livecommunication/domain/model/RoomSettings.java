package com.jtalk.livecommunication.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/** What the host can change about a room, as the people in it are told. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable
public record RoomSettings(String title, @Nullable String topic, int maxStageSeats) {
}
