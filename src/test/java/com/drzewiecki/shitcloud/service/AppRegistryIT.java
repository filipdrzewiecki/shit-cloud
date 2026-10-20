package com.drzewiecki.shitcloud.service;

import com.drzewiecki.shitcloud.IntegrationTest;
import com.drzewiecki.shitcloud.db.Timestamps;
import com.drzewiecki.shitcloud.model.AppDetail;
import com.drzewiecki.shitcloud.model.AppSummary;
import com.drzewiecki.shitcloud.model.DeployRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.http.HttpStatus.NOT_FOUND;

class AppRegistryIT extends IntegrationTest {

    @Autowired
    private AppRegistry registry;

    @Test
    void deploy_writesTheAppAndItsFirstVersion() {
        AppSummary deployed = registry.deploy(new DeployRequest("Kalendarz", "Kto jest w biurze", "<p>v1</p>"), "anna@example.com");

        Map<String, Object> app = app("kalendarz");
        assertThat(app).containsEntry("version", 1)
                .containsEntry("owner", "anna@example.com")
                .containsEntry("updated_by", "anna@example.com")
                .containsEntry("html", "<p>v1</p>");
        assertThat(app.get("created_at")).isNotNull();
        assertThat(deployed.version()).isEqualTo(1);

        assertThat(versions()).singleElement().satisfies(version -> {
            assertThat(version).containsEntry("app_slug", "kalendarz")
                    .containsEntry("version", 1)
                    .containsEntry("author", "anna@example.com")
                    .containsEntry("html", "<p>v1</p>");
        });
    }

    @Test
    void redeploy_keepsEveryVersionAndTheOriginalOwner() {
        registry.deploy(new DeployRequest("Kalendarz", null, "<p>v1</p>"), "anna@example.com");
        registry.deploy(new DeployRequest("Kalendarz", null, "<p>v2</p>"), "tomek@example.com");

        assertThat(app("kalendarz")).containsEntry("version", 2)
                .containsEntry("html", "<p>v2</p>")
                .containsEntry("owner", "anna@example.com")
                .containsEntry("updated_by", "tomek@example.com");

        assertThat(versions()).extracting(v -> v.get("version"), v -> v.get("author"), v -> v.get("html"))
                .containsExactly(
                        tuple(1, "anna@example.com", "<p>v1</p>"),
                        tuple(2, "tomek@example.com", "<p>v2</p>"));
    }

    @Test
    void failedVersionWrite_rollsBackTheApp() {
        // Every version insert now fails, after the app upsert: both must be undone.
        jdbc.sql("alter table app_versions add constraint no_versions check (false) not valid").update();
        try {
            assertThatThrownBy(() -> registry.deploy(new DeployRequest("Kalendarz", null, "<p>v1</p>"), "anna@example.com"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.sql("alter table app_versions drop constraint no_versions").update();
        }

        assertThat(registry.exists("kalendarz")).isFalse();
    }

    @Test
    void duplicateVersion_isRejectedByTheSchema() {
        registry.deploy(new DeployRequest("Kalendarz", null, "<p>v1</p>"), "anna@example.com");

        assertThatThrownBy(() -> jdbc.sql("""
                        insert into app_versions (app_slug, version, name, html, author, created_at)
                        values ('kalendarz', 1, 'Kalendarz', '<p>b</p>', 'y', now())""").update())
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void open_movesLastUsed_butAnArchivedAppStaysArchived() {
        Instant longAgo = Instant.now().minus(Duration.ofDays(60));
        insertApp("stara", longAgo, true);

        AppDetail opened = registry.open("stara");

        assertThat(opened.html()).isEqualTo("<p>stara</p>");
        assertThat(opened.app().archived()).isTrue();
        assertThat(opened.app().lastUsedAt()).isAfter(longAgo);
        assertThat(app("stara")).containsEntry("archived", true);
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
        insertApp("older", now.minus(Duration.ofDays(2)), false);
        insertApp("newer", now.minus(Duration.ofHours(1)), false);

        List<AppSummary> apps = registry.list();

        assertThat(apps).extracting(AppSummary::slug).containsExactly("newer", "older");
    }

    @Test
    void html_andExists_doNotTouchLastUsed() {
        Instant longAgo = Instant.now().minus(Duration.ofDays(3)).truncatedTo(ChronoUnit.MICROS);
        insertApp("cicha", longAgo, false);

        assertThat(registry.html("cicha")).isEqualTo("<p>cicha</p>");
        assertThat(registry.exists("cicha")).isTrue();
        assertThat(registry.exists("brak")).isFalse();
        assertThat(jdbc.sql("select last_used_at from apps where slug = 'cicha'")
                .query((row, i) -> Timestamps.read(row, "last_used_at")).single()).isEqualTo(longAgo);
    }

    private void insertApp(String slug, Instant lastUsedAt, boolean archived) {
        jdbc.sql("""
                        insert into apps (slug, name, html, owner, version, last_used_at, archived, created_at,
                                          updated_at, updated_by)
                        values (?, ?, ?, 'anna@example.com', 3, ?, ?, ?, ?, 'anna@example.com')""")
                .params(slug, slug, "<p>" + slug + "</p>", Timestamps.of(lastUsedAt), archived,
                        Timestamps.of(lastUsedAt), Timestamps.of(lastUsedAt))
                .update();
    }

    private Map<String, Object> app(String slug) {
        return jdbc.sql("select * from apps where slug = ?").param(slug).query().singleRow();
    }

    private List<Map<String, Object>> versions() {
        return jdbc.sql("select * from app_versions order by version").query().listOfRows();
    }
}
