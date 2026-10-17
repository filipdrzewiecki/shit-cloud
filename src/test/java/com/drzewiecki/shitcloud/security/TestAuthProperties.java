package com.drzewiecki.shitcloud.security;

import java.time.Duration;

/** Builds {@link AuthProperties} for unit tests, with the same claim and group names as production. */
final class TestAuthProperties {

    static final String SECRET = "unit-test-secret-0123456789abcdefghijklmnop";
    static final String NS = "https://shit-cloud-api/";

    private TestAuthProperties() {
    }

    static AuthProperties withGuest(String password, String secret, Duration ttl) {
        AuthProperties.Guest guest = new AuthProperties.Guest(password, secret, ttl, 3, Duration.ofMinutes(15));
        return new AuthProperties(
                "https://my-intelligence.eu.auth0.com/",
                "https://shit-cloud-api",
                "https://my-intelligence.eu.auth0.com/.well-known/jwks.json",
                NS + "email",
                NS + "name",
                NS + "groups",
                "ShitCloud Builder",
                "ShitCloud Admin",
                guest);
    }

    static AuthProperties withGuest(String password) {
        return withGuest(password, SECRET, Duration.ofDays(1));
    }
}
