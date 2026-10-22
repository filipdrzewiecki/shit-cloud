package com.drzewiecki.shitcloud.db;

import com.drzewiecki.shitcloud.IntegrationTest;
import com.drzewiecki.shitcloud.data.DataService;
import com.drzewiecki.shitcloud.data.DataWrite;
import com.drzewiecki.shitcloud.data.DependencyLog;
import com.drzewiecki.shitcloud.migration.MigrationService;
import com.drzewiecki.shitcloud.model.DeployRequest;
import com.drzewiecki.shitcloud.service.AppRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExpiryJobIT extends IntegrationTest {

    @Autowired
    private ExpiryJob expiryJob;

    @Autowired
    private AppRegistry appRegistry;

    @Autowired
    private DataService data;

    @Autowired
    private DependencyLog dependencies;

    @Autowired
    private MigrationService migrations;

    @Test
    void purge_removesOnlyWhatIsOlderThanItsRetention() {
        appRegistry.deploy(new DeployRequest("Kalendarz", null, "<p>v1</p>"), BUILDER_EMAIL);
        appRegistry.deploy(new DeployRequest("Kalendarz", null, "<p>v2</p>"), BUILDER_EMAIL);
        data.set("kalendarz", "days", "a", new DataWrite(Map.of("n", 1), null), "guest:Anna");
        data.set("kalendarz", "days", "a", new DataWrite(Map.of("n", 2), null), "guest:Anna");
        data.set("kalendarz", "days", "a", new DataWrite(Map.of("n", 3), null), "guest:Anna");
        // The first version and the first history entry are made 100 days old; retention is 90 days.
        jdbc.sql("update app_versions set created_at = now() - interval '100 days' where version = 1").update();
        jdbc.sql("update doc_history set changed_at = now() - interval '100 days' where version = 1").update();

        expiryJob.purge(Instant.now());

        assertThat(jdbc.sql("select version from app_versions").query(Integer.class).list()).containsExactly(2);
        assertThat(jdbc.sql("select version from doc_history").query(Long.class).list()).containsExactly(2L);
        // The app and its current data are never expired.
        assertThat(appRegistry.exists("kalendarz")).isTrue();
        assertThat(jdbc.sql("select count(*) from docs").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void purge_forgetsDependenciesUnusedForTheirRetention() {
        appRegistry.deploy(new DeployRequest("Kalendarz", null, "<p>x</p>"), BUILDER_EMAIL);
        appRegistry.deploy(new DeployRequest("Raport", null, "<p>x</p>"), BUILDER_EMAIL);
        dependencies.record("raport", "kalendarz", "days", DependencyLog.Mode.READ);
        dependencies.record("kalendarz", "kalendarz", "days", DependencyLog.Mode.WRITE);
        jdbc.sql("update app_deps set last_seen_at = now() - interval '100 days' where reader = 'raport'").update();

        expiryJob.purge(Instant.now());

        assertThat(jdbc.sql("select reader from app_deps").query(String.class).list()).containsExactly("kalendarz");
    }

    @Test
    void purge_dropsTheCopiesOfOldDataChanges_andProposalsNobodyApplied() {
        appRegistry.deploy(new DeployRequest("Kalendarz", null, "<p>x</p>"), BUILDER_EMAIL);
        data.set("kalendarz", "days", "a", new DataWrite(Map.of("n", 1), null), "guest:Anna");
        String code = "function migrate(doc) { return {m: doc.n}; }";
        long applied = migrations.propose("kalendarz", null, code, null, null, BUILDER_EMAIL).id();
        migrations.applyWithHtml("kalendarz", applied, "<p>y</p>", BUILDER_EMAIL);
        long stale = migrations.propose("kalendarz", null, code, null, null, BUILDER_EMAIL).id();
        long fresh = migrations.propose("kalendarz", null, code, null, null, BUILDER_EMAIL).id();
        jdbc.sql("update migrations set copy_until = now() - interval '1 day' where id = ?").param(applied).update();
        jdbc.sql("update migrations set proposed_at = now() - interval '31 days' where id = ?").param(stale).update();

        expiryJob.purge(Instant.now());

        assertThat(jdbc.sql("select count(*) from migration_docs").query(Long.class).single()).isZero();
        // The audit of the applied change stays; it just can no longer be undone.
        assertThat(jdbc.sql("select id from migrations order by id").query(Long.class).list()).containsExactly(applied, fresh);
        assertThat(jdbc.sql("select copy_until is null from migrations where id = ?").param(applied).query(Boolean.class).single())
                .isTrue();
    }

    @Test
    void purge_inTheFuture_removesEverythingPastRetention() {
        appRegistry.deploy(new DeployRequest("Kalendarz", null, "<p>v1</p>"), BUILDER_EMAIL);

        expiryJob.purge(Instant.now().plus(Duration.ofDays(91)));

        assertThat(jdbc.sql("select count(*) from app_versions").query(Long.class).single()).isZero();
    }
}
