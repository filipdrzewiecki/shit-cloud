package com.drzewiecki.shitcloud.controller;

import com.drzewiecki.shitcloud.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformControllerIT extends IntegrationTest {

    @Test
    void signedIn_getsTheLibraryBaseUrl() throws Exception {
        mvc.perform(get("/api/platform").with(guest("Anna")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.libraryBaseUrl").value("http://apps.test/"));
    }

    @Test
    void anonymous_isRefused() throws Exception {
        mvc.perform(get("/api/platform")).andExpect(status().isUnauthorized());
    }
}
