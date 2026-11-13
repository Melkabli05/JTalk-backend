package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.domain.dto.request.QualityReportRequest;
import com.jtalk.livecommunication.domain.mapper.ApiMapper;
import com.jtalk.livecommunication.domain.service.AdaptiveSubscriptionService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.validation.Validated;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import java.security.Principal;
import java.util.UUID;

@Controller("/api/v1/voice-rooms/{roomId}/quality")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.VIRTUAL)
@Validated
@RequiredArgsConstructor
class QualityTelemetryController {

    private final AdaptiveSubscriptionService adaptiveSubscription;
    private final ApiMapper mapper;

    /** A client reports its downstream quality; the server decides when to switch it into low-bandwidth mode. */
    @Post
    HttpResponse<Void> report(UUID roomId, Principal principal, @Body @Valid QualityReportRequest request) {
        adaptiveSubscription.onReport(roomId, UUID.fromString(principal.getName()), mapper.toReport(request));
        return HttpResponse.noContent();
    }
}
