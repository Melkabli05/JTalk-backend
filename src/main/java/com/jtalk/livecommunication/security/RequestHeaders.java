package com.jtalk.livecommunication.security;

/**
 * Authentication is performed upstream (API gateway); this service trusts the caller
 * identity forwarded in {@value #USER_ID}. Never expose this service directly to clients.
 */
public final class RequestHeaders {

    public static final String USER_ID = "X-User-Id";

    private RequestHeaders() {
    }
}
