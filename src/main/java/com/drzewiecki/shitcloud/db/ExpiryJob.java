package com.drzewiecki.shitcloud.db;

import com.drzewiecki.shitcloud.data.DataProperties;
import com.drzewiecki.shitcloud.migration.MigrationProperties;
import com.drzewiecki.shitcloud.service.AppsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Removes old app versions, old document history, unused dependency-log rows, the copies kept for undoing data
 * changes and data changes never applied, once a night. PostgreSQL has no TTL indexes, so the retention in
 * {@code apps.version-ttl}, {@code data.history-ttl}, {@code data.deps-ttl}, {@code migration.copy-ttl} (as
 * {@code migrations.copy_until}) and {@code migration.proposal-ttl} is enforced here; rows live up to a day past it. One API instance runs this; scaled out, it would need a lock.
 */
@Component
public class ExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ExpiryJob.class);

    private final JdbcClient jdbc;
    private final AppsProperties apps;
    private final DataProperties data;
    private final MigrationProperties migrations;

    public ExpiryJob(JdbcClient jdbc, AppsProperties apps, DataProperties data, MigrationProperties migrations) {
        this.jdbc = jdbc;
        this.apps = apps;
        this.data = data;
        this.migrations = migrations;
    }

    @Scheduled(cron = "${db.expiry-cron:0 30 3 * * *}")
    void nightly() {
        purge(Instant.now());
    }

    /** Deletes what is older than its retention at {@code now}. */
    public void purge(Instant now) {
        int versions = jdbc.sql("delete from app_versions where created_at < ?")
                .param(Timestamps.of(now.minus(apps.versionTtl())))
                .update();
        int history = jdbc.sql("delete from doc_history where changed_at < ?")
                .param(Timestamps.of(now.minus(data.historyTtl())))
                .update();
        int deps = jdbc.sql("delete from app_deps where last_seen_at < ?")
                .param(Timestamps.of(now.minus(data.depsTtl())))
                .update();
        // The audit row of an applied change stays; only its copy goes, which ends the chance to undo it.
        int copies = jdbc.sql("delete from migration_docs where migration_id in (select id from migrations where copy_until < ?)")
                .param(Timestamps.of(now))
                .update();
        jdbc.sql("update migrations set copy_until = null where copy_until < ?").param(Timestamps.of(now)).update();
        int proposals = jdbc.sql("delete from migrations where status = 'proposed' and proposed_at < ?")
                .param(Timestamps.of(now.minus(migrations.proposalTtl())))
                .update();
        log.info("Expired {} app versions, {} history entries, {} unused dependencies, {} copied documents of data "
                + "changes and {} unapplied data changes", versions, history, deps, copies, proposals);
    }
}
