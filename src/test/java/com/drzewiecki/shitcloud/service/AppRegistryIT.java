package com.drzewiecki.shitcloud.service;

import com.drzewiecki.shitcloud.IntegrationTest;
import com.drzewiecki.shitcloud.model.AppDetail;
import com.drzewiecki.shitcloud.model.AppRecord;
import com.drzewiecki.shitcloud.model.AppSummary;
import com.drzewiecki.shitcloud.model.AppVersion;
import com.drzewiecki.shitcloud.model.DeployRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

class AppRegistryIT extends IntegrationTest {

    @Autowired
    private AppRegistry registry;

    @Test
    void deploy_writesTheAppAndItsFirstVersion() {
        AppSummary deployed = registry.deploy(new DeployRequest("Kalendarz", "Kto jest w biurze", "<p>v1</p>"), "anna@example.com");

        AppRecord app = mongo.findById("kalendarz", AppRecord.class);
        assertThat(app).isNotNull();
        assertThat(app.version()).isEqualTo(1);
        assertThat(app.owner()).isEqualTo("anna@example.com");
        assertThat(app.updatedBy()).isEqualTo("anna@example.com");
        assertThat(app.html()).isEqualTo("<p>v1</p>");
        assertThat(app.createdAt()).isNotNull();
        assertThat(deployed.version()).isEqualTo(1);

        List<AppVersion> versions = mongo.findAll(AppVersion.class);
        assertThat(versions).singleElement().satisfies(version -> {
            assertThat(version.appId()).isEqualTo("kalendarz");
            assertThat(version.version()).isEqualTo(1);
            assertThat(version.author()).isEqualTo("anna@example.com");
            assertThat(version.html()).isEqualTo("<p>v1</p>");
        });
    }

    @Test
    void redeploy_keepsEveryVersionAndTheOriginalOwner() {
        registry.deploy(new DeployRequest("Kalendarz", null, "<p>v1</p>"), "anna@example.com");
        registry.deploy(new DeployRequest("Kalendarz", null, "<p>v2</p>"), "tomek@example.com");

        AppRecord app = mongo.findById("kalendarz", AppRecord.class);
        assertThat(app.version()).isEqualTo(2);
        assertThat(app.html()).isEqualTo("<p>v2</p>");
        assertThat(app.owner()).isEqualTo("anna@example.com");
        assertThat(app.updatedBy()).isEqualTo("tomek@example.com");

        List<AppVersion> versions = mongo.findAll(AppVersion.class);
        assertThat(versions).extracting(AppVersion::version, AppVersion::author, AppVersion::html)
                .containsExactlyInAnyOrder(
                        tuple(1, "anna@example.com", "<p>v1</p>"),
                        tuple(2, "tomek@example.com", "<p>v2</p>"));
    }

    @Test
    void failedVersionWrite_rollsBackTheApp() {
        // A leftover version 1 makes the version insert fail after the app upsert: both must be undone.
        mongo.insert(new AppVersion(null, "kalendarz", 1, "Kalendarz", null, "<p>old</p>", "x", Instant.now()));

        assertThatThrownBy(() -> registry.deploy(new DeployRequest("Kalendarz", null, "<p>v1</p>"), "anna@example.com"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(CONFLICT));

        assertThat(mongo.findById("kalendarz", AppRecord.class)).isNull();
    }

    @Test
    void duplicateVersion_isRejectedByTheIndex() {
        AppVersion first = new AppVersion(null, "kalendarz", 1, "Kalendarz", null, "<p>a</p>", "x", Instant.now());
        AppVersion second = new AppVersion(null, "kalendarz", 1, "Kalendarz", null, "<p>b</p>", "y", Instant.now());
        mongo.insert(first);

        assertThatThrownBy(() -> mongo.insert(second)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void open_movesLastUsedAndUnarchives() {
        Instant longAgo = Instant.now().minus(Duration.ofDays(60));
        mongo.insert(new AppRecord("stara", "Stara", null, "<p>old</p>", "anna@example.com", 3, longAgo, true,
                longAgo, longAgo, "anna@example.com"));

        AppDetail opened = registry.open("stara");

        assertThat(opened.html()).isEqualTo("<p>old</p>");
        assertThat(opened.app().archived()).isFalse();
        assertThat(opened.app().lastUsedAt()).isAfter(longAgo);
        AppRecord stored = mongo.findById("stara", AppRecord.class);
        assertThat(stored.archived()).isFalse();
        assertThat(stored.lastUsedAt()).isAfter(longAgo);
    }

    @Test
    void open_unknownApp_is404() {
        assertThatThrownBy(() -> registry.open("nope"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(NOT_FOUND));
    }

    @Test
    void list_isMostRecentlyUsedFirstWithoutHtml() {
        Instant now = Instant.now();
        mongo.insert(new AppRecord("older", "Older", null, "<p>o</p>", "a", 1, now.minus(Duration.ofDays(2)), false,
                now, now, "a"));
        mongo.insert(new AppRecord("newer", "Newer", null, "<p>n</p>", "a", 1, now.minus(Duration.ofHours(1)), false,
                now, now, "a"));

        List<AppSummary> apps = registry.list();

        assertThat(apps).extracting(AppSummary::slug).containsExactly("newer", "older");
    }
}
