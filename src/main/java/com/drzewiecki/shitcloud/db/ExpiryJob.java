package com.drzewiecki.shitcloud.db;

import com.drzewiecki.shitcloud.data.DataProperties;
import com.drzewiecki.shitcloud.service.AppsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Removes old app versions, old document history and unused dependency-log rows once a night. PostgreSQL has no
 * TTL indexes, so the retention in {@code apps.version-ttl}, {@code data.history-ttl} and {@code data.deps-ttl} is
 * enforced here; rows live up to a day past it. One API instance runs this; scaled out, it would need a lock.
 */
@Component
public class ExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ExpiryJob.class);

    private final JdbcClient jdbc;
    private final AppsProperties apps;
    private final DataProperties data;

    public ExpiryJob(JdbcClient jdbc, AppsProperties apps, DataProperties data) {
        this.jdbc = jdbc;
        this.apps = apps;
        this.data = data;
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
        log.info("Expired {} app versions, {} history entries and {} unused dependencies", versions, history, deps);
    }
}
