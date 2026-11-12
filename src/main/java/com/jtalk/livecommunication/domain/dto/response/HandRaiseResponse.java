package com.jtalk.livecommunication.domain.dto.response;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable.Serializable
public record HandRaiseResponse(int queuePosition) {
}
