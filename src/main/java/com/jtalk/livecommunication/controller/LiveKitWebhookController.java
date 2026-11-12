package com.jtalk.livecommunication.controller;

import com.jtalk.livecommunication.domain.service.LiveKitWebhookService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;

/** Receives LiveKit webhooks. The signature is verified in the service; the raw body is needed for that. */
@Controller("/webhooks/livekit")
@ExecuteOn(TaskExecutors.VIRTUAL)
class LiveKitWebhookController {

    private final LiveKitWebhookService webhooks;

    LiveKitWebhookController(LiveKitWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @Post
    @Consumes({"application/webhook+json", MediaType.APPLICATION_JSON})
    HttpResponse<Void> receive(@Body String body, @Nullable @Header(HttpHeaders.AUTHORIZATION) String authorization) {
        webhooks.handle(body, authorization);
        return HttpResponse.ok();
    }
}
