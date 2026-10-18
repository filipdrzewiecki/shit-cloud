package com.drzewiecki.shitcloud.controller;

import com.drzewiecki.shitcloud.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AppControllerIT extends IntegrationTest {

    private static final String DEPLOY = """
            {"name": "%s", "html": "<p>hello</p>"}""";

    @Test
    void guest_canListAndOpenApps() throws Exception {
        mvc.perform(deploy("Lunch poll", builder()))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/apps").with(guest("Anna")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("lunch-poll"))
                .andExpect(jsonPath("$[0].html").doesNotExist());
        mvc.perform(get("/api/apps/lunch-poll").with(guest("Anna")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app.slug").value("lunch-poll"))
                .andExpect(jsonPath("$.html").value("<p>hello</p>"));
    }

    @Test
    void unknownApp_answers404() throws Exception {
        mvc.perform(get("/api/apps/no-such-app").with(guest("Anna")))
                .andExpect(status().isNotFound());
    }

    @Test
    void guest_cannotDeploy() throws Exception {
        mvc.perform(deploy("Guest app", guest("Anna")))
                .andExpect(status().isForbidden());
    }

    @Test
    void identityProviderUserWithoutRole_cannotDeploy() throws Exception {
        mvc.perform(deploy("No role app", idpUser("someone@example.com", "Someone")))
                .andExpect(status().isForbidden());
    }

    @Test
    void builder_deploysAndIsRecordedAsOwner() throws Exception {
        mvc.perform(deploy("Builder app", builder()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("builder-app"))
                .andExpect(jsonPath("$.owner").value(BUILDER_EMAIL))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void admin_canDeploy() throws Exception {
        mvc.perform(deploy("Admin app", admin()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.owner").value(ADMIN_EMAIL));
    }

    @Test
    void redeployBySomeoneElse_bumpsTheVersionAndKeepsTheOwner() throws Exception {
        mvc.perform(deploy("Shared app", builder()))
                .andExpect(status().isCreated());

        mvc.perform(deploy("Shared app", admin()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.owner").value(BUILDER_EMAIL));
    }

    @Test
    void tooLargeHtml_answers413() throws Exception {
        String html = "x".repeat(5 * 1024 * 1024 + 1);
        String body = "{\"name\": \"Big app\", \"html\": \"" + html + "\"}";

        mvc.perform(post("/api/apps").with(builder())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isPayloadTooLarge());
    }

    private static MockHttpServletRequestBuilder deploy(String name, RequestPostProcessor caller) {
        return post("/api/apps").with(caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content(DEPLOY.formatted(name));
    }
}
