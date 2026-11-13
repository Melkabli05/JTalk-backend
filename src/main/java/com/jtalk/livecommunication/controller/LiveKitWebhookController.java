package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.domain.service.LiveKitWebhookService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import lombok.RequiredArgsConstructor;

/** Receives LiveKit webhooks. The signature is verified in the service; the raw body is needed for that. */
@Controller("/webhooks/livekit")
@Secured(SecurityRule.IS_ANONYMOUS)
@ExecuteOn(TaskExecutors.VIRTUAL)
@RequiredArgsConstructor
class LiveKitWebhookController {

    private final LiveKitWebhookService webhooks;

    @Post
    @Consumes({"application/webhook+json", MediaType.APPLICATION_JSON})
    void receive(@Body String body, @Nullable @Header(HttpHeaders.AUTHORIZATION) String authorization) {
        webhooks.handle(body, authorization);
    }
}
