package com.drzewiecki.shitcloud.controller;

import com.drzewiecki.shitcloud.IntegrationTest;
import com.drzewiecki.shitcloud.model.DeployRequest;
import com.drzewiecki.shitcloud.service.AppRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DataControllerIT extends IntegrationTest {

    private static final String DAYS = "/api/data/kalendarz/days";

    @Autowired
    private AppRegistry appRegistry;

    @BeforeEach
    void deployTheApp() {
        appRegistry.deploy(new DeployRequest("Kalendarz", null, "<p>x</p>"), BUILDER_EMAIL);
    }

    @Test
    void guestWrites_builderReads() throws Exception {
        mvc.perform(put(DAYS + "/anna_2026-10-20").with(guest("Anna"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"data\": {\"type\": \"off\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("anna_2026-10-20"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.updatedBy").value("guest:Anna"));

        mvc.perform(post(DAYS + "/query").with(builder())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"filter\": {\"type\": \"off\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("anna_2026-10-20"))
                .andExpect(jsonPath("$[0].data.type").value("off"));
    }

    @Test
    void appOnScreen_isRecordedAsTheReader_afterSuccessfulCallsOnly() throws Exception {
        appRegistry.deploy(new DeployRequest("Office Report", null, "<p>x</p>"), BUILDER_EMAIL);

        mvc.perform(post(DAYS + "/query").with(guest("Anna")).header(DataController.READER, "office-report"))
                .andExpect(status().isOk());
        mvc.perform(put(DAYS + "/a").with(guest("Anna")).header(DataController.READER, "kalendarz")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"data\": {\"n\": 1}}"))
                .andExpect(status().isOk());
        // Refused calls and calls without the header are not recorded.
        mvc.perform(post("/api/data/kalendarz/people/query").with(guest("Anna")).header(DataController.READER, "office-report")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"filter\": {\"$where\": \"1\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/data/kalendarz/votes/query").with(guest("Anna"))).andExpect(status().isOk());

        assertThat(jdbc.sql("select reader || ' ' || space || ' ' || collection || ' ' || mode from app_deps")
                .query(String.class).list())
                .containsExactlyInAnyOrder("office-report kalendarz days read", "kalendarz kalendarz days write");
    }

    @Test
    void add_answers201WithTheNewId() throws Exception {
        mvc.perform(post("/api/data/kalendarz/votes").with(guest("Anna"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"data\": {\"place\": \"Pizza\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty());
    }

    @Test
    void remove_answersWhetherSomethingWasRemoved() throws Exception {
        mvc.perform(delete(DAYS + "/nothing").with(guest("Anna")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(false));
    }

    @Test
    void queryWithoutBody_returnsEverything() throws Exception {
        mvc.perform(post(DAYS + "/query").with(guest("Anna")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void errors_carryACodeAndAMessage() throws Exception {
        mvc.perform(post(DAYS + "/query").with(guest("Anna"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"filter\": {\"$where\": \"1\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").isNotEmpty());

        mvc.perform(post("/api/data/no-such-app/days/query").with(guest("Anna")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_SUCH_APP"));

        mvc.perform(post(DAYS + "/query").with(guest("Anna"))
                        .contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void versionConflict_answers409() throws Exception {
        String write = "{\"data\": {\"n\": 1}, \"expectedVersion\": 5}";

        mvc.perform(put(DAYS + "/a").with(guest("Anna")).contentType(MediaType.APPLICATION_JSON).content(write))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void withoutToken_answers401() throws Exception {
        mvc.perform(post(DAYS + "/query"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void appsPort_doesNotServeTheDataApi() throws Exception {
        mvc.perform(post(DAYS + "/query").with(appsPort()).with(guest("Anna")))
                .andExpect(status().isNotFound());
    }
}
