package com.jtalk.livecommunication.dto.request;

import com.jtalk.livecommunication.config.CommunicationConstants;
import com.jtalk.livecommunication.config.VisibleText;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param maxStageSeats seats on the stage including the host; defaults to {@link CommunicationConstants#DEFAULT_STAGE_SEATS}
 */
@Serdeable.Deserializable
public record CreateVoiceRoomRequest(
        @NotBlank @Size(max = 80) @VisibleText(message = "must not be blank") String title,
        @Nullable @Size(max = 255) @VisibleText(allowEmpty = true) String topic,
        @NotBlank @Size(max = 10) String targetLanguage,
        @NotBlank @Size(max = 10) String nativeLanguage,
        @Nullable @Min(CommunicationConstants.MIN_STAGE_SEATS) @Max(CommunicationConstants.MAX_STAGE_SEATS) Integer maxStageSeats) {

    public int seatsOrDefault() {
        return maxStageSeats == null ? CommunicationConstants.DEFAULT_STAGE_SEATS : maxStageSeats;
    }
}
