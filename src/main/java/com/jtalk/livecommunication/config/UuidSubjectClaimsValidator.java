package com.jtalk.livecommunication.config;

import io.micronaut.security.token.Claims;
import io.micronaut.security.token.jwt.validator.GenericJwtClaimsValidator;
import jakarta.inject.Singleton;

import java.util.UUID;

/**
 * Every API call identifies the user by the token's subject, which must be their UUID. A token without one is rejected
 * with 401 here, by Micronaut Security, so no controller ever has to cope with a malformed user id.
 */
@Singleton
class UuidSubjectClaimsValidator<T> implements GenericJwtClaimsValidator<T> {

    @Override
    public boolean validate(Claims claims, T request) {
        try {
            UUID.fromString(String.valueOf(claims.get(Claims.SUBJECT)));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
