package com.drzewiecki.shitcloud.service;

import com.drzewiecki.shitcloud.IntegrationTest;
import com.drzewiecki.shitcloud.data.DataService;
import com.drzewiecki.shitcloud.data.DataWrite;
import com.drzewiecki.shitcloud.data.DocView;
import com.drzewiecki.shitcloud.data.ExportView;
import com.drzewiecki.shitcloud.model.AppSummary;
import com.drzewiecki.shitcloud.model.AppUsage;
import com.drzewiecki.shitcloud.model.DeleteResult;
import com.drzewiecki.shitcloud.model.DeployRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/** Archive, restore, replace the HTML, delete with data, usage and export of a deployed app. */
class AppManagementIT extends IntegrationTest {

    @Autowired
    private AppRegistry registry;

    @Autowired
    private DataService data;

    @BeforeEach
    void kalendarzWithData() {
        registry.deploy(new DeployRequest("Kalendarz", "Kto jest w biurze", "<p>v1</p>"), BUILDER_EMAIL);
        data.set("kalendarz", "days", "a", new DataWrite(Map.of("n", 1), null), "guest:Anna");
        data.set("kalendarz", "days", "a", new DataWrite(Map.of("n", 2), null), "guest:Anna");
        data.set("kalendarz", "teams", "t", new DataWrite(Map.of("name", "Backend"), null), "guest:Anna");
    }

    @Test
    void archive_hidesTheAppAndRecordsWhoAndWhen_dataStays() {
        AppSummary archived = registry.archive("kalendarz", "tomek@example.com");

        assertThat(archived.archived()).isTrue();
        assertThat(archived.archivedBy()).isEqualTo("tomek@example.com");
        assertThat(archived.archivedAt()).isNotNull();
        assertThat(registry.usage("kalendarz").documents()).isEqualTo(2);
        assertThat(registry.list()).singleElement().satisfies(app -> assertThat(app.archived()).isTrue());
    }

    @Test
    void archivingTwice_keepsWhoArchivedFirst() {
        AppSummary first = registry.archive("kalendarz", "tomek@example.com");
        AppSummary second = registry.archive("kalendarz", "anna@example.com");

        assertThat(second.archivedBy()).isEqualTo("tomek@example.com");
        assertThat(second.archivedAt()).isEqualTo(first.archivedAt());
    }

    @Test
    void restore_bringsTheAppBack() {
        registry.archive("kalendarz", "tomek@example.com");

        AppSummary restored = registry.restore("kalendarz");

        assertThat(restored.archived()).isFalse();
        assertThat(restored.archivedAt()).isNull();
        assertThat(restored.archivedBy()).isNull();
    }

    @Test
    void openingAnArchivedApp_doesNotRestoreIt() {
        registry.archive("kalendarz", "tomek@example.com");

        assertThat(registry.open("kalendarz").app().archived()).isTrue();
    }

    @Test
    void deployingByName_restoresAnArchivedApp() {
        registry.archive("kalendarz", "tomek@example.com");

        AppSummary deployed = registry.deploy(new DeployRequest("Kalendarz", null, "<p>v2</p>"), BUILDER_EMAIL);

        assertThat(deployed.archived()).isFalse();
        assertThat(deployed.archivedBy()).isNull();
    }

    @Test
    void replaceHtml_isTheNextVersion_nameDescriptionArchiveAndDataStay() {
        registry.archive("kalendarz", "tomek@example.com");

        AppSummary replaced = registry.replaceHtml("kalendarz", "<p>v2</p>", "anna@example.com");

        assertThat(replaced.version()).isEqualTo(2);
        assertThat(replaced.name()).isEqualTo("Kalendarz");
        assertThat(replaced.description()).isEqualTo("Kto jest w biurze");
        assertThat(replaced.archived()).isTrue();
        assertThat(registry.html("kalendarz")).isEqualTo("<p>v2</p>");
        assertThat(registry.usage("kalendarz")).isEqualTo(new AppUsage(2, 2, 2));
        assertThat(jdbc.sql("select author from app_versions where app_slug = 'kalendarz' and version = 2")
                .query(String.class).single()).isEqualTo("anna@example.com");
    }

    @Test
    void replaceHtml_ofAMissingApp_is404_andCreatesNothing() {
        assertThatThrownBy(() -> registry.replaceHtml("kalendar", "<p>x</p>", "anna@example.com"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(NOT_FOUND));
        assertThat(registry.exists("kalendar")).isFalse();
    }

    @Test
    void delete_removesTheAppVersionsDocumentsAndHistory_andNothingOfOtherApps() {
        registry.deploy(new DeployRequest("Inna", null, "<p>x</p>"), BUILDER_EMAIL);
        data.set("inna", "days", "a", new DataWrite(Map.of("n", 1), null), "guest:Anna");
        data.set("inna", "days", "a", new DataWrite(Map.of("n", 2), null), "guest:Anna");

        DeleteResult result = registry.delete("kalendarz", "kalendarz", BUILDER_EMAIL);

        assertThat(result).isEqualTo(new DeleteResult(2, 1, 1));
        assertThat(registry.exists("kalendarz")).isFalse();
        assertThat(count("select count(*) from docs where space = 'kalendarz'")).isZero();
        assertThat(count("select count(*) from app_versions where app_slug = 'kalendarz'")).isZero();
        assertThat(count("select count(*) from doc_history where space = 'kalendarz'")).isZero();
        assertThat(count("select count(*) from docs where space = 'inna'")).isEqualTo(1);
        assertThat(count("select count(*) from doc_history where space = 'inna'")).isEqualTo(1);
    }

    @Test
    void delete_needsTheSlugAsConfirmation() {
        assertThatThrownBy(() -> registry.delete("kalendarz", null, BUILDER_EMAIL))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(BAD_REQUEST));
        assertThatThrownBy(() -> registry.delete("kalendarz", "Kalendarz", BUILDER_EMAIL))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(registry.exists("kalendarz")).isTrue();
    }

    @Test
    void delete_ofAMissingApp_is404() {
        assertThatThrownBy(() -> registry.delete("brak", "brak", BUILDER_EMAIL))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(NOT_FOUND));
    }

    @Test
    void theSameNameAfterADelete_startsFromScratch() {
        registry.delete("kalendarz", "kalendarz", BUILDER_EMAIL);

        AppSummary again = registry.deploy(new DeployRequest("Kalendarz", null, "<p>new</p>"), "anna@example.com");

        assertThat(again.version()).isEqualTo(1);
        assertThat(again.owner()).isEqualTo("anna@example.com");
        assertThat(registry.usage("kalendarz")).isEqualTo(new AppUsage(0, 0, 1));
    }

    @Test
    void export_groupsEveryDocumentByCollection() {
        ExportView export = data.export("kalendarz");

        assertThat(export.app()).isEqualTo(new ExportView.App("kalendarz", "Kalendarz", 1));
        assertThat(export.exportedAt()).isNotNull();
        assertThat(export.collections()).containsOnlyKeys("days", "teams");
        assertThat(export.collections().get("days")).singleElement().satisfies(doc -> {
            assertThat(doc.id()).isEqualTo("a");
            assertThat(doc.data()).isEqualTo(Map.of("n", 2));
            assertThat(doc.version()).isEqualTo(2);
        });
        assertThat(export.collections().get("teams")).extracting(DocView::id).containsExactly("t");
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
