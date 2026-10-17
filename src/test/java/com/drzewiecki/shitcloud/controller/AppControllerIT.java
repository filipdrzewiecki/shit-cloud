package com.drzewiecki.shitcloud.controller;

import com.drzewiecki.shitcloud.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AppControllerIT extends IntegrationTest {

    private static final String DEPLOY = """
            {"name": "%s", "html": "<p>hello</p>"}""";

    @Test
    void guest_canListAndOpenApps() throws Exception {
        mvc.perform(get("/api/apps").with(guest("Anna")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/apps/lunch-poll").with(guest("Anna")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app.slug").value("lunch-poll"));
    }

    @Test
    void guest_cannotDeploy() throws Exception {
        mvc.perform(post("/api/apps").with(guest("Anna"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DEPLOY.formatted("Guest app")))
                .andExpect(status().isForbidden());
    }

    @Test
    void identityProviderUserWithoutRole_cannotDeploy() throws Exception {
        mvc.perform(post("/api/apps").with(idpUser("someone@example.com", "Someone"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DEPLOY.formatted("No role app")))
                .andExpect(status().isForbidden());
    }

    @Test
    void builder_deploysAndIsRecordedAsOwner() throws Exception {
        mvc.perform(post("/api/apps").with(builder())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DEPLOY.formatted("Builder app")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("builder-app"))
                .andExpect(jsonPath("$.owner").value(BUILDER_EMAIL));
    }

    @Test
    void admin_canDeploy() throws Exception {
        mvc.perform(post("/api/apps").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DEPLOY.formatted("Admin app")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.owner").value(ADMIN_EMAIL));
    }
}
