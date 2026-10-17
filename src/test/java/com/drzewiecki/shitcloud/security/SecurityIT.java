package com.drzewiecki.shitcloud.security;

import com.drzewiecki.shitcloud.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityIT extends IntegrationTest {

    @ParameterizedTest
    @CsvSource({
            "GET, /api/me",
            "GET, /api/apps",
            "GET, /api/apps/lunch-poll",
            "POST, /api/apps",
            "POST, /api/chat"
    })
    void withoutToken_everyApiEndpointAnswers401(String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void health_isOpenForTheRailwayHealthcheck() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void garbageToken_answers401() throws Exception {
        mvc.perform(get("/api/me").with(bearer("not-a-jwt")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenFromAnUnknownIssuer_answers401() throws Exception {
        String token = unsignedToken("{\"iss\":\"https://attacker.example/\",\"sub\":\"x\"}");

        mvc.perform(get("/api/me").with(bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void guestIssuerWithAForgedSignature_answers401() throws Exception {
        String token = unsignedToken("{\"iss\":\"" + GuestTokenService.ISSUER + "\",\"sub\":\"guest:Mallory\","
                + "\"aud\":[\"https://shit-cloud-api\"],\"name\":\"Mallory\"}");

        mvc.perform(get("/api/me").with(bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void preflightFromTheGui_isAllowed() throws Exception {
        mvc.perform(options("/api/me")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    }

    @Test
    void preflightFromAnotherOrigin_isRefused() throws Exception {
        mvc.perform(options("/api/me")
                        .header("Origin", "https://attacker.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    /** A structurally valid JWT with a bogus signature; enough to reach issuer routing. */
    private static String unsignedToken(String payloadJson) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".c2lnbmF0dXJl";
    }
}
