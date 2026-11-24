package com.jtalk.livecommunication.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable
public record AudiencePage(List<ParticipantPreview> audiencePreview, boolean hasMoreAudience, @Nullable UUID nextCursor) {
}
