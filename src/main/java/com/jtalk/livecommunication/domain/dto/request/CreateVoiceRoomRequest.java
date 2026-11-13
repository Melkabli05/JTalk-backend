package com.jtalk.livecommunication.domain.dto.request;

import com.jtalk.livecommunication.config.CommunicationConstants;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param maxStageSeats seats on the stage including the host; defaults to {@link CommunicationConstants#DEFAULT_STAGE_SEATS}
 * @param allowWhisper  whether speakers may whisper to a participant; defaults to true
 */
@Serdeable.Deserializable
public record CreateVoiceRoomRequest(
        @NotBlank @Size(max = 80) String title,
        @Nullable @Size(max = 255) String topic,
        @NotBlank @Size(max = 10) String targetLanguage,
        @NotBlank @Size(max = 10) String nativeLanguage,
        @Nullable @Min(CommunicationConstants.MIN_STAGE_SEATS) @Max(CommunicationConstants.MAX_STAGE_SEATS) Integer maxStageSeats,
        @Nullable Boolean allowWhisper) {

    public int seatsOrDefault() {
        return maxStageSeats == null ? CommunicationConstants.DEFAULT_STAGE_SEATS : maxStageSeats;
    }

    public boolean whisperAllowed() {
        return allowWhisper == null || allowWhisper;
    }
}
