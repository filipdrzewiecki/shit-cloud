package com.drzewiecki.shitcloud.controller;

import com.drzewiecki.shitcloud.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MeControllerIT extends IntegrationTest {

    @Test
    void builder_isDescribedFromTheToken() throws Exception {
        mvc.perform(get("/api/me").with(builder()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(BUILDER_EMAIL))
                .andExpect(jsonPath("$.displayName").value("Builder"))
                .andExpect(jsonPath("$.groups[0]").value("ShitCloud Builder"))
                .andExpect(jsonPath("$.builder").value(true))
                .andExpect(jsonPath("$.admin").value(false))
                .andExpect(jsonPath("$.guest").value(false));
    }

    @Test
    void admin_isAlsoABuilder() throws Exception {
        mvc.perform(get("/api/me").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ADMIN_EMAIL))
                .andExpect(jsonPath("$.builder").value(true))
                .andExpect(jsonPath("$.admin").value(true));
    }

    @Test
    void guest_hasNoRole() throws Exception {
        mvc.perform(get("/api/me").with(guest("Tomek")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("guest:Tomek"))
                .andExpect(jsonPath("$.groups").isEmpty())
                .andExpect(jsonPath("$.builder").value(false))
                .andExpect(jsonPath("$.guest").value(true));
    }
}
