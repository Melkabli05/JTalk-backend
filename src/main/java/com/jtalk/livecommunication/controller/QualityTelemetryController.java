package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.domain.dto.request.QualityReportRequest;
import com.jtalk.livecommunication.domain.model.QualityReport;
import com.jtalk.livecommunication.domain.service.AdaptiveSubscriptionService;
import com.jtalk.livecommunication.security.RequestHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import io.micronaut.validation.Validated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

@Controller("/api/v1/voice-rooms/{roomId}/quality")
@ExecuteOn(TaskExecutors.VIRTUAL)
@Validated
class QualityTelemetryController {

    private final AdaptiveSubscriptionService adaptiveSubscription;

    QualityTelemetryController(AdaptiveSubscriptionService adaptiveSubscription) {
        this.adaptiveSubscription = adaptiveSubscription;
    }

    @Post
    HttpResponse<Void> report(UUID roomId,
                              @Header(RequestHeaders.USER_ID) @NotBlank String userId,
                              @Body @Valid QualityReportRequest request) {
        adaptiveSubscription.onReport(roomId, userId, new QualityReport(request.lossPercent(), request.downstreamKbps()));
        return HttpResponse.noContent();
    }
}
