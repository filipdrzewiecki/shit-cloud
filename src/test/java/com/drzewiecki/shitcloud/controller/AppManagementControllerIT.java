package com.drzewiecki.shitcloud.controller;

import com.drzewiecki.shitcloud.IntegrationTest;
import com.drzewiecki.shitcloud.data.DataService;
import com.drzewiecki.shitcloud.data.DataWrite;
import com.drzewiecki.shitcloud.model.DeployRequest;
import com.drzewiecki.shitcloud.service.AppRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AppManagementControllerIT extends IntegrationTest {

    @Autowired
    private AppRegistry registry;

    @Autowired
    private DataService data;

    @BeforeEach
    void kalendarz() {
        registry.deploy(new DeployRequest("Kalendarz", null, "<p>v1</p>"), BUILDER_EMAIL);
        data.set("kalendarz", "days", "a", new DataWrite(Map.of("n", 1), null), "guest:Anna");
    }

    @Test
    void builder_archivesAndRestores() throws Exception {
        mvc.perform(post("/api/apps/kalendarz/archive").with(builder()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true))
                .andExpect(jsonPath("$.archivedBy").value(BUILDER_EMAIL));
        mvc.perform(get("/api/apps/kalendarz").with(guest("Anna")))
                .andExpect(jsonPath("$.app.archived").value(true));
        mvc.perform(post("/api/apps/kalendarz/restore").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(false));
    }

    @Test
    void builder_replacesTheHtml() throws Exception {
        mvc.perform(put("/api/apps/kalendarz/html").with(builder())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"html\": \"<p>v2</p>\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.name").value("Kalendarz"));
    }

    @Test
    void replacingWithTooMuchHtml_answers413() throws Exception {
        String html = "x".repeat(5 * 1024 * 1024 + 1);

        mvc.perform(put("/api/apps/kalendarz/html").with(builder())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"html\": \"" + html + "\"}"))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void builder_deletesWithConfirmation() throws Exception {
        mvc.perform(delete("/api/apps/kalendarz").with(builder()))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/apps/kalendarz").param("confirm", "kalendarz").with(builder()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents").value(1))
                .andExpect(jsonPath("$.versions").value(1));
        mvc.perform(get("/api/apps/kalendarz").with(builder()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/data/kalendarz/days/query").with(guest("Anna")))
                .andExpect(status().isNotFound());
    }

    @Test
    void everyone_seesUsageAndDownloadsTheData() throws Exception {
        mvc.perform(get("/api/apps/kalendarz/usage").with(guest("Anna")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents").value(1))
                .andExpect(jsonPath("$.collections").value(1))
                .andExpect(jsonPath("$.versions").value(1));
        mvc.perform(get("/api/apps/kalendarz/export").with(guest("Anna")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("filename=\"kalendarz-data.json\"")))
                .andExpect(jsonPath("$.app.name").value("Kalendarz"))
                .andExpect(jsonPath("$.collections.days[0].id").value("a"))
                .andExpect(jsonPath("$.collections.days[0].data.n").value(1));
    }

    @Test
    void missingApp_answers404Everywhere() throws Exception {
        mvc.perform(post("/api/apps/brak/archive").with(builder())).andExpect(status().isNotFound());
        mvc.perform(post("/api/apps/brak/restore").with(builder())).andExpect(status().isNotFound());
        mvc.perform(put("/api/apps/brak/html").with(builder())
                .contentType(MediaType.APPLICATION_JSON).content("{\"html\": \"<p>x</p>\"}")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/apps/brak").param("confirm", "brak").with(builder())).andExpect(status().isNotFound());
        mvc.perform(get("/api/apps/brak/usage").with(builder())).andExpect(status().isNotFound());
        mvc.perform(get("/api/apps/brak/export").with(builder()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_SUCH_APP"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"archive", "restore", "html", "delete"})
    void guestsAndUsersWithoutTheRole_cannotManage(String action) throws Exception {
        mvc.perform(request(action).with(guest("Anna"))).andExpect(status().isForbidden());
        mvc.perform(request(action).with(idpUser("someone@example.com", "Someone"))).andExpect(status().isForbidden());
    }

    private static MockHttpServletRequestBuilder request(String action) {
        return switch (action) {
            case "archive" -> post("/api/apps/kalendarz/archive");
            case "restore" -> post("/api/apps/kalendarz/restore");
            case "html" -> put("/api/apps/kalendarz/html").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"html\": \"<p>x</p>\"}");
            default -> delete("/api/apps/kalendarz").param("confirm", "kalendarz");
        };
    }
}
