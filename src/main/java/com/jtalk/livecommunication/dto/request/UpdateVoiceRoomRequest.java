package com.jtalk.livecommunication.dto.request;

import com.jtalk.livecommunication.config.CommunicationConstants;
import com.jtalk.livecommunication.config.VisibleText;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** Partial update: omitted fields keep their value. */
@Serdeable.Deserializable
public record UpdateVoiceRoomRequest(
        @Nullable @VisibleText(message = "must not be blank") @Size(max = 80) String title,
        @Nullable @VisibleText(allowEmpty = true) @Size(max = 255) String topic,
        @Nullable @Min(CommunicationConstants.MIN_STAGE_SEATS) @Max(CommunicationConstants.MAX_STAGE_SEATS) Integer maxStageSeats) {
}
