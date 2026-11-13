package com.jtalk.livecommunication.controller;

import io.micronaut.core.bind.ArgumentBinder.BindingResult;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.AnnotatedRequestArgumentBinder;
import jakarta.inject.Singleton;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves {@link CurrentUser}. The token's subject is guaranteed to be a UUID by
 * {@code UuidSubjectClaimsValidator}, so a request that reaches a controller always has one.
 */
@Singleton
class CurrentUserBinder implements AnnotatedRequestArgumentBinder<CurrentUser, UUID> {

    @Override
    public Class<CurrentUser> getAnnotationType() {
        return CurrentUser.class;
    }

    @Override
    public BindingResult<UUID> bind(ArgumentConversionContext<UUID> context, HttpRequest<?> source) {
        Optional<UUID> userId = source.getUserPrincipal().map(principal -> UUID.fromString(principal.getName()));
        return () -> userId;
    }
}
