package com.drzewiecki.shitcloud.apps;

import com.drzewiecki.shitcloud.IntegrationTest;
import com.drzewiecki.shitcloud.model.DeployRequest;
import com.drzewiecki.shitcloud.mongo.MongoCollections;
import com.drzewiecki.shitcloud.service.AppRegistry;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Path mode, as on {@code test}: {@code http://apps.test/{slug}/}. Per-app host mode is in {@code AppOriginTest}. */
class AppDocumentIT extends IntegrationTest {

    private static final String HTML = "<!doctype html><html><head><title>Lunch</title></head>"
            + "<body><script>app()</script></body></html>";

    @Autowired
    private AppRegistry appRegistry;

    @BeforeEach
    void deployAnApp() {
        appRegistry.deploy(new DeployRequest("Lunch poll", null, HTML), BUILDER_EMAIL);
    }

    @Test
    void document_isTheAppWithTheSdkFirst() throws Exception {
        mvc.perform(get("/lunch-poll/").with(appsPort()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/html;charset=UTF-8"))
                .andExpect(content().string(startsWith("<!doctype html><html><head><script>// ShitCloud SDK")))
                .andExpect(content().string(containsString("<body><script>app()</script></body>")));
    }

    @Test
    void document_carriesTheIsolationHeaders() throws Exception {
        mvc.perform(get("/lunch-poll/").with(appsPort()))
                .andExpect(header().string("Content-Security-Policy", containsString("connect-src 'none'")))
                .andExpect(header().string("Content-Security-Policy", containsString("form-action 'none'")))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("Content-Security-Policy",
                        containsString("frame-ancestors http://localhost:3000")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Cache-Control", "no-store"))
                // Framing is governed by frame-ancestors; X-Frame-Options: DENY would block every app.
                .andExpect(header().doesNotExist("X-Frame-Options"));
    }

    @Test
    void document_needsNoSignIn_andIgnoresTokens() throws Exception {
        mvc.perform(get("/lunch-poll/").with(appsPort()).with(bearer("not-a-jwt")))
                .andExpect(status().isOk());
    }

    @Test
    void withoutTrailingSlash_redirectsToTheAppsPath() throws Exception {
        mvc.perform(get("/lunch-poll").with(appsPort()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/lunch-poll/"));
    }

    @Test
    void unknownApp_answers404() throws Exception {
        mvc.perform(get("/no-such-app/").with(appsPort()))
                .andExpect(status().isNotFound());
    }

    @Test
    void rootInPathMode_answers404() throws Exception {
        mvc.perform(get("/").with(appsPort()))
                .andExpect(status().isNotFound());
    }

    @Test
    void servingTheDocument_doesNotCountAsUsingTheApp() throws Exception {
        Instant before = lastUsedAt();

        mvc.perform(get("/lunch-poll/").with(appsPort()))
                .andExpect(status().isOk());

        assertThat(lastUsedAt()).isEqualTo(before);
    }

    @Test
    void redeploy_isServedAtOnce() throws Exception {
        appRegistry.deploy(new DeployRequest("Lunch poll", null, "<p>v2</p>"), BUILDER_EMAIL);

        mvc.perform(get("/lunch-poll/").with(appsPort()))
                .andExpect(content().string(containsString("<p>v2</p>")));
    }

    private Instant lastUsedAt() {
        Document app = mongo.findOne(query(where("_id").is("lunch-poll")), Document.class, MongoCollections.APPS);
        return app.getDate("lastUsedAt").toInstant();
    }
}
