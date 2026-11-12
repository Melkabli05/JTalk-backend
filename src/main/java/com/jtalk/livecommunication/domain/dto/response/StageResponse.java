package com.jtalk.livecommunication.domain.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/** Empty lists must stay in the payload: an empty stage is information, not absence. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable.Serializable
public record StageResponse(List<String> speakers, List<String> handRaiseQueue) {
}
