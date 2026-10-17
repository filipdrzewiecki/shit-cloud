package com.drzewiecki.shitcloud.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The test properties allow 3 failures per window. */
class LoginAttemptLimiterTest {

    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(TestAuthProperties.withGuest("meat"));

    @Test
    void blocks_onceTheLimitIsReached() {
        limiter.recordFailure("1.1.1.1");
        limiter.recordFailure("1.1.1.1");
        assertThat(limiter.blocked("1.1.1.1")).isFalse();

        limiter.recordFailure("1.1.1.1");

        assertThat(limiter.blocked("1.1.1.1")).isTrue();
    }

    @Test
    void countsEachIpSeparately() {
        limiter.recordFailure("1.1.1.1");
        limiter.recordFailure("1.1.1.1");
        limiter.recordFailure("1.1.1.1");

        assertThat(limiter.blocked("2.2.2.2")).isFalse();
    }

    @Test
    void reset_clearsTheIp() {
        limiter.recordFailure("1.1.1.1");
        limiter.recordFailure("1.1.1.1");
        limiter.recordFailure("1.1.1.1");

        limiter.reset("1.1.1.1");

        assertThat(limiter.blocked("1.1.1.1")).isFalse();
    }

    @Test
    void unknownIp_isNotBlocked() {
        assertThat(limiter.blocked("9.9.9.9")).isFalse();
    }
}
