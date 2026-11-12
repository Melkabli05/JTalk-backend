package com.jtalk.livecommunication.domain.dto.request;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;

import java.util.List;

/**
 * @param subscribedTrackSids the audio tracks the client is subscribed to right now
 * @param primaryTrackSid     the one it wants to keep if its connection is struggling
 */
@Serdeable.Deserializable
public record QualityReportRequest(
        @DecimalMin("0") @DecimalMax("100") double lossPercent,
        @Min(0) int downstreamKbps,
        @Nullable List<String> subscribedTrackSids,
        @Nullable String primaryTrackSid) {
}
