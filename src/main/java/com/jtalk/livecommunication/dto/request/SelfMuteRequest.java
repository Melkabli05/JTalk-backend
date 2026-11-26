package com.jtalk.livecommunication.dto.request;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable.Deserializable
public record SelfMuteRequest(boolean muted) {
}
