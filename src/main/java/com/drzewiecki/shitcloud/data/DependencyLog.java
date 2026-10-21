package com.drzewiecki.shitcloud.data;

import com.drzewiecki.shitcloud.db.Timestamps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Which app used which data: one row per reader app, space, collection and mode, with when it was first and last
 * seen. The reader is whatever the host page says is on screen, so the log informs (warnings in the chat, "Used by"
 * on the app page) and never guards anything. Recording is best effort: it must never fail the call it describes.
 */
@Component
public class DependencyLog {

    private static final Logger log = LoggerFactory.getLogger(DependencyLog.class);

    /** Apps poll every few seconds; a row's last use is written at most this often. */
    static final Duration REFRESH = Duration.ofHours(1);

    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{0,99}");

    public enum Mode {
        READ, WRITE;

        String column() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final JdbcClient jdbc;

    public DependencyLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param reader the app on screen; null, blank or not a slug → nothing is recorded, as is an app that does not exist
     */
    public void record(String reader, String space, String collection, Mode mode) {
        if (reader == null || !SLUG.matcher(reader).matches()) {
            return;
        }
        Instant now = Instant.now();
        try {
            // "where exists": a reader that is no app inserts nothing instead of breaking the foreign key.
            jdbc.sql("""
                            insert into app_deps (reader, space, collection, mode, first_seen_at, last_seen_at)
                            select ?, ?, ?, ?, ?, ? where exists (select 1 from apps where slug = ?)
                            on conflict (space, collection, mode, reader) do update set last_seen_at = excluded.last_seen_at
                            where app_deps.last_seen_at < ?""")
                    .params(reader, space, collection, mode.column(), Timestamps.of(now), Timestamps.of(now), reader,
                            Timestamps.of(now.minus(REFRESH)))
                    .update();
        } catch (DataAccessException e) {
            log.warn("Could not record that {} used {}/{} ({})", reader, space, collection, mode, e);
        }
    }

    /**
     * Who used the space's data, the space's own app included; most recently used first.
     */
    public List<DependencyView> users(String space) {
        if (jdbc.sql("select count(*) from apps where slug = ?").param(space).query(Long.class).single() == 0) {
            throw new DataException(HttpStatus.NOT_FOUND, DataException.NO_SUCH_APP, "No app " + space);
        }
        return jdbc.sql("""
                        select d.reader, a.name, a.archived, d.collection, d.mode, d.first_seen_at, d.last_seen_at
                        from app_deps d join apps a on a.slug = d.reader
                        where d.space = ?
                        order by d.last_seen_at desc, d.reader, d.collection, d.mode""")
                .param(space)
                .query((row, i) -> new DependencyView(row.getString("reader"), row.getString("name"),
                        row.getBoolean("archived"), row.getString("collection"), row.getString("mode"),
                        Timestamps.read(row, "first_seen_at"), Timestamps.read(row, "last_seen_at")))
                .list();
    }
}
