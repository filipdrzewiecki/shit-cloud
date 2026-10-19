package com.drzewiecki.shitcloud.apps;

import com.drzewiecki.shitcloud.IntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LibraryMirrorIT extends IntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"alpine.min.js", "tailwind.js", "chart.min.js"})
    void libraries_areServedOnTheAppsPortWithCaching(String file) throws Exception {
        mvc.perform(get("/_lib/" + file).with(appsPort()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("javascript")))
                .andExpect(header().string("Cache-Control", "max-age=86400, public"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/_lib/jquery.js", "/_lib/", "/_lib/../application.properties"})
    void unknownFiles_answer404(String path) throws Exception {
        mvc.perform(get(path).with(appsPort()))
                .andExpect(status().isNotFound());
    }
}
