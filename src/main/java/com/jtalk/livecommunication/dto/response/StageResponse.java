package com.jtalk.livecommunication.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;
import java.util.UUID;

/** Empty lists must stay in the payload: an empty queue is information, not absence. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable.Serializable
public record StageResponse(List<UUID> speakers, List<UUID> handRaiseQueue) {
}
