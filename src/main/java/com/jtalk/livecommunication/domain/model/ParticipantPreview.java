package com.jtalk.livecommunication.domain.model;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.UUID;

@Serdeable
public record ParticipantPreview(UUID userId, @Nullable String displayName, @Nullable String avatarUrl,
                                 @Nullable String nativeLanguage, boolean isReconnecting) {
}
