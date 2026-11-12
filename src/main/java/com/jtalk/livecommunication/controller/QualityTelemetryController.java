package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.domain.dto.request.QualityReportRequest;
import com.jtalk.livecommunication.domain.service.AdaptiveSubscriptionService;
import com.jtalk.livecommunication.domain.mapper.ApiMapper;
import com.jtalk.livecommunication.security.CallerId;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.http.annotation.Post;
import io.micronaut.validation.Validated;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

@Controller("/api/v1/voice-rooms/{roomId}/quality")
@ExecuteOn(TaskExecutors.VIRTUAL)
@Validated
@RequiredArgsConstructor
class QualityTelemetryController {

    private final AdaptiveSubscriptionService adaptiveSubscription;
    private final ApiMapper mapper;

    @Post
    HttpResponse<Void> report(UUID roomId,
                              @CallerId String userId,
                              @Body @Valid QualityReportRequest request) {
        adaptiveSubscription.onReport(roomId, userId, mapper.toReport(request));
        return HttpResponse.noContent();
    }
}
