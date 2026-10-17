package com.drzewiecki.shitcloud.security;

import com.drzewiecki.shitcloud.model.GuestToken;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuestTokenServiceTest {

    private final GuestTokenService service = new GuestTokenService(TestAuthProperties.withGuest("meat"));

    @Test
    void issuedToken_decodesToTheGuest() {
        GuestToken token = service.issue("Anna");

        Jwt jwt = service.decoder().decode(token.token());

        assertThat(jwt.getSubject()).isEqualTo("guest:Anna");
        assertThat(jwt.getClaimAsString(GuestTokenService.NAME_CLAIM)).isEqualTo("Anna");
        // Read as a string: the guest issuer is a name, not a URL, so getIssuer() cannot represent it.
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(GuestTokenService.ISSUER);
        assertThat(jwt.getAudience()).containsExactly("https://shit-cloud-api");
    }

    @Test
    void issuedToken_expiresAfterTheConfiguredTtl() {
        Instant before = Instant.now();

        GuestToken token = service.issue("Anna");

        assertThat(token.expiresAt()).isBetween(before.plus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(1)));
    }

    @Test
    void passwordMatches_onlyTheConfiguredPassword() {
        assertThat(service.passwordMatches("meat")).isTrue();
        assertThat(service.passwordMatches("meat ")).isFalse();
        assertThat(service.passwordMatches("")).isFalse();
        assertThat(service.passwordMatches(null)).isFalse();
    }

    @Test
    void changingThePassword_invalidatesEarlierTokens() {
        String token = service.issue("Anna").token();
        GuestTokenService rotated = new GuestTokenService(TestAuthProperties.withGuest("new-password"));

        assertThatThrownBy(() -> rotated.decoder().decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void expiredToken_isRejected() {
        // Issued a day and two minutes ago with a one-day TTL: expired beyond the decoder's 60 s clock skew.
        Instant issuedAt = Instant.now().minus(Duration.ofDays(1)).minus(Duration.ofMinutes(2));
        GuestTokenService past = new GuestTokenService(TestAuthProperties.withGuest("meat"),
                Clock.fixed(issuedAt, ZoneOffset.UTC));
        String token = past.issue("Anna").token();

        assertThatThrownBy(() -> service.decoder().decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithAnotherSecret_isRejected() {
        GuestTokenService other = new GuestTokenService(TestAuthProperties.withGuest(
                "meat", "another-secret-0123456789abcdefghijklmnop", Duration.ofDays(1)));
        String token = other.issue("Anna").token();

        assertThatThrownBy(() -> service.decoder().decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void blankPassword_disablesGuestSignIn() {
        GuestTokenService disabled = new GuestTokenService(TestAuthProperties.withGuest(""));

        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.passwordMatches("")).isFalse();
    }

    @Test
    void passwordWithShortSecret_failsAtStartup() {
        AuthProperties properties = TestAuthProperties.withGuest("meat", "too-short", Duration.ofDays(1));

        assertThatThrownBy(() -> new GuestTokenService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GUEST_TOKEN_SECRET");
    }
}
