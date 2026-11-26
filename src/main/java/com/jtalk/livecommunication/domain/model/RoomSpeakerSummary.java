package com.jtalk.livecommunication.domain.model;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.UUID;

@Serdeable
public record RoomSpeakerSummary(
        UUID id,
        @Nullable String username,
        @Nullable String displayName,
        @Nullable String avatarUrl,
        @Nullable String countryCode,
        @Nullable String nativeLanguage) {
}
