package com.jtalk.livecommunication.support;

import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.annotation.ClientFilter;
import io.micronaut.http.annotation.RequestFilter;

/** Lets tests issue plain GETs (room lookup, discovery) as an anonymous "viewer" without repeating a token. */
@ClientFilter
class DefaultBearerFilter {

    @RequestFilter
    void authenticateReads(MutableHttpRequest<?> request) {
        if (request.getMethod() == HttpMethod.GET && !request.getHeaders().contains(HttpHeaders.AUTHORIZATION)) {
            request.bearerAuth(ApiTestBase.token("viewer"));
        }
    }
}
