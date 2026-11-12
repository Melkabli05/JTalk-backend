package com.jtalk.livecommunication.domain.dto.request;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;

@Serdeable.Deserializable
public record QualityReportRequest(
        @DecimalMin("0") @DecimalMax("100") double lossPercent,
        @Min(0) int downstreamKbps) {
}
