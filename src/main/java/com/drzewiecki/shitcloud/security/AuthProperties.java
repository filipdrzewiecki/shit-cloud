package com.drzewiecki.shitcloud.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Two ways in. Builders and admins come through the identity provider, whose claim names and group
 * names are configuration so that the on-prem switch to AD is not a code change. Everyone else uses
 * the shared guest password.
 */
@ConfigurationProperties("auth")
public record AuthProperties(
        String issuerUri,
        String audience,
        String jwksUri,
        String idClaim,
        String nameClaim,
        String groupsClaim,
        String buildersGroup,
        String adminsGroup,
        Guest guest) {

    public record Guest(
            String password,
            String tokenSecret,
            Duration tokenTtl,
            int maxFailures,
            Duration failureWindow) {

        /** Fails closed: a forgotten variable must never mean that an empty password works. */
        public boolean enabled() {
            return password != null && !password.isBlank();
        }
    }
}
