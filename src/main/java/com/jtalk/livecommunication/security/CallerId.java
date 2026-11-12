package com.jtalk.livecommunication.security;

import io.micronaut.http.annotation.Header;
import jakarta.validation.constraints.NotBlank;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds the caller's identity. Authentication happens upstream (API gateway), which forwards the user id in
 * {@code X-User-Id}; a missing or blank header is rejected with 400. Never expose this service directly to clients.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@Header("X-User-Id")
@NotBlank
public @interface CallerId {
}
