package com.drzewiecki.shitcloud.controller;

import com.drzewiecki.shitcloud.IntegrationTest;
import com.drzewiecki.shitcloud.data.DependencyLog;
import com.drzewiecki.shitcloud.model.DeployRequest;
import com.drzewiecki.shitcloud.service.AppRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DepsControllerIT extends IntegrationTest {

    @Autowired
    private AppRegistry appRegistry;

    @Autowired
    private DependencyLog log;

    @BeforeEach
    void anAppReadByAnother() {
        appRegistry.deploy(new DeployRequest("Team Calendar", null, "<p>x</p>"), BUILDER_EMAIL);
        appRegistry.deploy(new DeployRequest("Office Report", null, "<p>x</p>"), BUILDER_EMAIL);
        log.record("office-report", "team-calendar", "marks", DependencyLog.Mode.READ);
    }

    @Test
    void builders_seeWhoUsesTheData() throws Exception {
        mvc.perform(get("/api/deps/team-calendar").with(builder()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].app").value("office-report"))
                .andExpect(jsonPath("$[0].appName").value("Office Report"))
                .andExpect(jsonPath("$[0].archived").value(false))
                .andExpect(jsonPath("$[0].collection").value("marks"))
                .andExpect(jsonPath("$[0].mode").value("read"))
                .andExpect(jsonPath("$[0].lastSeenAt").isNotEmpty());
    }

    @Test
    void guestsAndUsers_areRefused() throws Exception {
        for (RequestPostProcessor caller : List.of(guest("Anna"), idpUser("user@example.com", "User"))) {
            mvc.perform(get("/api/deps/team-calendar").with(caller)).andExpect(status().isForbidden());
        }
    }

    @Test
    void unknownApp_is404WithACode() throws Exception {
        mvc.perform(get("/api/deps/nope").with(builder()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_SUCH_APP"));
    }
}
