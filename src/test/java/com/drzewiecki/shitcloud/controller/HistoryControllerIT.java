package com.drzewiecki.shitcloud.controller;

import com.drzewiecki.shitcloud.IntegrationTest;
import com.drzewiecki.shitcloud.data.DataService;
import com.drzewiecki.shitcloud.data.DataWrite;
import com.drzewiecki.shitcloud.model.DeployRequest;
import com.drzewiecki.shitcloud.service.AppRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HistoryControllerIT extends IntegrationTest {

    @Autowired
    private AppRegistry registry;

    @Autowired
    private DataService data;

    private long firstState;

    @BeforeEach
    void aDocumentChangedOnce() {
        registry.deploy(new DeployRequest("Team Calendar", null, "<p>x</p>"), BUILDER_EMAIL);
        data.set("team-calendar", "days", "d1", new DataWrite(Map.of("type", "office"), null), "guest:Anna");
        data.set("team-calendar", "days", "d1", new DataWrite(Map.of("type", "off"), null), "guest:Piotr");
        firstState = jdbc.sql("select id from doc_history").query(Long.class).single();
    }

    @Test
    void builders_browseCollectionsDocumentsAndHistory() throws Exception {
        mvc.perform(get("/api/apps/team-calendar/data").with(builder()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].collection").value("days"))
                .andExpect(jsonPath("$[0].documents").value(1))
                .andExpect(jsonPath("$[0].removed").value(0));

        mvc.perform(get("/api/apps/team-calendar/data/days").param("prefix", "d").with(builder()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("d1"))
                .andExpect(jsonPath("$.items[0].version").value(2))
                .andExpect(jsonPath("$.hasMore").value(false));

        mvc.perform(get("/api/apps/team-calendar/data/days/d1/history").with(builder()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.data.type").value("off"))
                .andExpect(jsonPath("$.versions[0].historyId").value(firstState))
                .andExpect(jsonPath("$.versions[0].data.type").value("office"))
                .andExpect(jsonPath("$.versions[0].changedBy").value("guest:Piotr"));
    }

    @Test
    void builders_restoreAnEarlierState() throws Exception {
        mvc.perform(post("/api/apps/team-calendar/data/days/d1/restore").with(builder())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"historyId\":" + firstState + ",\"expectedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.data.type").value("office"));
    }

    @Test
    void refusals_haveACode() throws Exception {
        String stale = "{\"historyId\":" + firstState + ",\"expectedVersion\":1}";
        mvc.perform(post("/api/apps/team-calendar/data/days/d1/restore").with(builder())
                        .contentType(MediaType.APPLICATION_JSON).content(stale))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mvc.perform(post("/api/apps/team-calendar/data/days/d1/restore").with(builder())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"historyId\":999999,\"expectedVersion\":2}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_SUCH_VERSION"));
        mvc.perform(post("/api/apps/team-calendar/data/days/d1/restore").with(builder())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"historyId\":" + firstState + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(get("/api/apps/nope/data").with(builder()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_SUCH_APP"));
        mvc.perform(get("/api/apps/team-calendar/data/days").param("limit", "500").with(builder()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void guestsAndUsers_areRefused() throws Exception {
        for (RequestPostProcessor caller : List.of(guest("Anna"), idpUser("user@example.com", "User"))) {
            mvc.perform(get("/api/apps/team-calendar/data").with(caller)).andExpect(status().isForbidden());
            mvc.perform(get("/api/apps/team-calendar/data/days").with(caller)).andExpect(status().isForbidden());
            mvc.perform(get("/api/apps/team-calendar/data/days/d1/history").with(caller)).andExpect(status().isForbidden());
            mvc.perform(post("/api/apps/team-calendar/data/days/d1/restore").with(caller)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"historyId\":" + firstState + ",\"expectedVersion\":2}"))
                    .andExpect(status().isForbidden());
        }
    }
}
