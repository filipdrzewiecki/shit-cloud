package com.drzewiecki.shitcloud.apps;

import com.drzewiecki.shitcloud.IntegrationTest;
import com.drzewiecki.shitcloud.model.DeployRequest;
import com.drzewiecki.shitcloud.service.AppRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Each port answers only its own paths, signed in or not. */
class PortFenceIT extends IntegrationTest {

    @Autowired
    private AppRegistry appRegistry;

    @BeforeEach
    void deployAnApp() {
        appRegistry.deploy(new DeployRequest("Lunch poll", null, "<p>hi</p>"), BUILDER_EMAIL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/me", "/api/apps", "/api/apps/lunch-poll", "/actuator/health", "/actuator",
            "/api/../_lib/alpine.min.js", "/_lib/../api/me", "/actuator/", "/api", "/error"})
    void appsPort_doesNotAnswerTheApi(String path) throws Exception {
        mvc.perform(get(path).with(appsPort()).with(guest("Anna")))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/_lib/alpine.min.js", "/lunch-poll/", "/lunch-poll", "/", "/favicon.ico", "/api"})
    void apiPort_doesNotAnswerAppsOrTheMirror(String path) throws Exception {
        mvc.perform(get(path).with(guest("Anna")))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/_lib/alpine.min.js", "/lunch-poll/"})
    void apiPort_answers404_evenWithoutAToken(String path) throws Exception {
        // 404, not 401: the API's origin must not even hint that these paths exist.
        mvc.perform(get(path))
                .andExpect(status().isNotFound());
    }
}
