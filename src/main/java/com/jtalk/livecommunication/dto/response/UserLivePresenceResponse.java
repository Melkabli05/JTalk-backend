package com.jtalk.livecommunication.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.UUID;

/** Whether a user is currently present in an active voice room, and if so which room. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable
public record UserLivePresenceResponse(
        UUID userId,
        boolean live,
        @Nullable UUID roomId) {
}
