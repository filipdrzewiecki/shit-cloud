package com.drzewiecki.shitcloud.controller;

import com.drzewiecki.shitcloud.IntegrationTest;
import com.drzewiecki.shitcloud.data.DataService;
import com.drzewiecki.shitcloud.data.DataWrite;
import com.drzewiecki.shitcloud.migration.MigrationService;
import com.drzewiecki.shitcloud.model.DeployRequest;
import com.drzewiecki.shitcloud.service.AppRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MigrationControllerIT extends IntegrationTest {

    private static final String NEW_HTML = "<!doctype html><html><body>v2</body></html>";

    @Autowired
    private AppRegistry registry;

    @Autowired
    private DataService data;

    @Autowired
    private MigrationService migrations;

    private long proposed;

    @BeforeEach
    void aCheckedDataChange() {
        registry.deploy(new DeployRequest("Team Calendar", null, "<!doctype html><html><body>v1</body></html>"), BUILDER_EMAIL);
        data.set("team-calendar", "days", "d1", new DataWrite(Map.of("type", "off"), null), "guest:Anna");
        proposed = migrations.propose("team-calendar", List.of("days"),
                "function migrate(doc) { return {kind: doc.type}; }", "type becomes kind", null, BUILDER_EMAIL).id();
    }

    @Test
    void replaceHtml_withTheChange_convertsTheData_thenItCanBeListedDownloadedAndUndone() throws Exception {
        mvc.perform(put("/api/apps/team-calendar/html").with(builder()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"html\":\"" + NEW_HTML + "\",\"migrationId\":" + proposed + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        assertThat(jdbc.sql("select data::text from docs where doc_id = 'd1'").query(String.class).single())
                .isEqualTo("{\"kind\": \"off\"}");

        mvc.perform(get("/api/apps/team-calendar/migrations").with(builder()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(proposed))
                .andExpect(jsonPath("$[0].status").value("applied"))
                .andExpect(jsonPath("$[0].summary").value("type becomes kind"))
                .andExpect(jsonPath("$[0].result.changed").value(1))
                .andExpect(jsonPath("$[0].undoable").value(true));

        mvc.perform(get("/api/migrations/{id}/copy", proposed).with(builder()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        containsString("team-calendar-before-change-" + proposed + ".json")))
                .andExpect(jsonPath("$.collections.days[0].id").value("d1"))
                .andExpect(jsonPath("$.collections.days[0].data.type").value("off"));

        mvc.perform(post("/api/migrations/{id}/undo", proposed).with(builder()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restored").value(1))
                .andExpect(jsonPath("$.removed").value(0))
                .andExpect(jsonPath("$.htmlVersion").value(3));
        assertThat(jdbc.sql("select data::text from docs where doc_id = 'd1'").query(String.class).single())
                .isEqualTo("{\"type\": \"off\"}");
    }

    @Test
    void replaceHtml_withoutAChange_leavesTheDataAlone() throws Exception {
        mvc.perform(put("/api/apps/team-calendar/html").with(builder()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"html\":\"" + NEW_HTML + "\"}"))
                .andExpect(status().isOk());

        assertThat(jdbc.sql("select data::text from docs where doc_id = 'd1'").query(String.class).single())
                .isEqualTo("{\"type\": \"off\"}");
    }

    @Test
    void refusals_haveACode() throws Exception {
        String body = "{\"html\":\"" + NEW_HTML + "\",\"migrationId\":" + proposed + "}";
        mvc.perform(put("/api/apps/team-calendar/html").with(builder()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(put("/api/apps/team-calendar/html").with(builder()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MIGRATION_STALE"));

        mvc.perform(post("/api/migrations/{id}/undo", 999).with(builder()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_SUCH_MIGRATION"));
        mvc.perform(get("/api/apps/nope/migrations").with(builder()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_SUCH_APP"));
    }

    @Test
    void guestsAndUsers_areRefused() throws Exception {
        for (RequestPostProcessor caller : List.of(guest("Anna"), idpUser("user@example.com", "User"))) {
            mvc.perform(get("/api/apps/team-calendar/migrations").with(caller)).andExpect(status().isForbidden());
            mvc.perform(post("/api/migrations/{id}/undo", proposed).with(caller)).andExpect(status().isForbidden());
            mvc.perform(get("/api/migrations/{id}/copy", proposed).with(caller)).andExpect(status().isForbidden());
            mvc.perform(put("/api/apps/team-calendar/html").with(caller).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"html\":\"" + NEW_HTML + "\",\"migrationId\":" + proposed + "}"))
                    .andExpect(status().isForbidden());
        }
    }
}
