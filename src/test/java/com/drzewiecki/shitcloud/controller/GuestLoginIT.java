package com.drzewiecki.shitcloud.controller;

import com.drzewiecki.shitcloud.IntegrationTest;
import com.drzewiecki.shitcloud.security.AuthProperties;
import com.drzewiecki.shitcloud.security.GuestTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The limiter is a singleton shared by the whole test context, so every test signs in from its own
 * client IP; otherwise failures in one test would block another.
 */
class GuestLoginIT extends IntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthProperties authProperties;

    @Test
    void correctPassword_returnsATokenThatSignsTheGuestIn() throws Exception {
        String body = signIn("10.0.0.1", GUEST_PASSWORD, "Anna")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").exists())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        String token = json.get("token").asText();

        mvc.perform(get("/api/me").with(bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("guest:Anna"))
                .andExpect(jsonPath("$.displayName").value("Anna"))
                .andExpect(jsonPath("$.guest").value(true))
                .andExpect(jsonPath("$.builder").value(false))
                .andExpect(jsonPath("$.admin").value(false));
    }

    @Test
    void nameIsTrimmed() throws Exception {
        signIn("10.0.0.2", GUEST_PASSWORD, "  Anna  ")
                .andExpect(status().isOk());
    }

    @Test
    void wrongPassword_answers401() throws Exception {
        signIn("10.0.0.3", "wrong", "Anna")
                .andExpect(status().isUnauthorized());
    }

    @Test
    void blankName_answers400() throws Exception {
        signIn("10.0.0.4", GUEST_PASSWORD, "   ")
                .andExpect(status().isBadRequest());
    }

    @Test
    void tooLongName_answers400() throws Exception {
        signIn("10.0.0.5", GUEST_PASSWORD, "x".repeat(41))
                .andExpect(status().isBadRequest());
    }

    @Test
    void afterTooManyFailures_evenTheCorrectPasswordAnswers429() throws Exception {
        String ip = "10.0.0.6";
        for (int i = 0; i < authProperties.guest().maxFailures(); i++) {
            signIn(ip, "wrong", "Anna").andExpect(status().isUnauthorized());
        }

        signIn(ip, GUEST_PASSWORD, "Anna")
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void aForgedForwardedPrefix_doesNotEscapeTheLimit() throws Exception {
        String ip = "10.0.0.7";
        for (int i = 0; i < authProperties.guest().maxFailures(); i++) {
            signIn(ip, "wrong", "Anna").andExpect(status().isUnauthorized());
        }

        signIn("1.2.3.4, " + ip, GUEST_PASSWORD, "Anna")
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void successfulSignIn_resetsTheFailureCount() throws Exception {
        String ip = "10.0.0.8";
        for (int i = 0; i < authProperties.guest().maxFailures() - 1; i++) {
            signIn(ip, "wrong", "Anna").andExpect(status().isUnauthorized());
        }
        signIn(ip, GUEST_PASSWORD, "Anna").andExpect(status().isOk());

        signIn(ip, "wrong", "Anna").andExpect(status().isUnauthorized());
        signIn(ip, GUEST_PASSWORD, "Anna").andExpect(status().isOk());
    }

    @Test
    void tokenIssuedUnderAnEarlierPassword_isRejected() throws Exception {
        AuthProperties.Guest current = authProperties.guest();
        AuthProperties.Guest earlier = new AuthProperties.Guest("earlier-password", current.tokenSecret(),
                current.tokenTtl(), current.maxFailures(), current.failureWindow());
        AuthProperties earlierProperties = new AuthProperties(authProperties.issuerUri(), authProperties.audience(),
                authProperties.jwksUri(), authProperties.idClaim(), authProperties.nameClaim(),
                authProperties.groupsClaim(), authProperties.buildersGroup(), authProperties.adminsGroup(), earlier);
        String token = new GuestTokenService(earlierProperties).issue("Anna").token();

        mvc.perform(get("/api/me").with(bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions signIn(String forwardedFor, String password, String name) throws Exception {
        String body = objectMapper.writeValueAsString(new Credentials(password, name));
        return mvc.perform(post("/api/auth/guest")
                .header("X-Forwarded-For", forwardedFor)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private record Credentials(String password, String name) {
    }
}
