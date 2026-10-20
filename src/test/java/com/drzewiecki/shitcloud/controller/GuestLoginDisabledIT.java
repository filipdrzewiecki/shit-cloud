package com.drzewiecki.shitcloud.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Without GUEST_PASSWORD the guest door is shut, and an empty password must not open it. */
@SpringBootTest(properties = {"auth.guest.password=", "apps.url-pattern=http://apps.test/{slug}/"})
@AutoConfigureMockMvc
class GuestLoginDisabledIT {

    @Autowired
    private MockMvc mvc;

    @Test
    void guestSignIn_answers404() throws Exception {
        mvc.perform(post("/api/auth/guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"\",\"name\":\"Anna\"}"))
                .andExpect(status().isNotFound());
    }
}
