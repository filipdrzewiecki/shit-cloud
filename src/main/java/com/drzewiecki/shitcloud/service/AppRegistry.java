package com.drzewiecki.shitcloud.service;

import com.drzewiecki.shitcloud.apps.AppOrigin;
import com.drzewiecki.shitcloud.apps.PortFence;
import com.drzewiecki.shitcloud.db.Timestamps;
import com.drzewiecki.shitcloud.model.AppDetail;
import com.drzewiecki.shitcloud.model.AppRecord;
import com.drzewiecki.shitcloud.model.AppSummary;
import com.drzewiecki.shitcloud.model.AppUsage;
import com.drzewiecki.shitcloud.model.DeleteResult;
import com.drzewiecki.shitcloud.model.DeployRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE;

/**
 * The app registry: {@code apps} holds each app with its current HTML, {@code app_versions} every deploy. Apps
 * are identified by the slug of their name, so deploying the same name again is a new version.
 */
@Service
public class AppRegistry {

    private static final Logger log = LoggerFactory.getLogger(AppRegistry.class);

    private static final RowMapper<AppRecord> APP = (row, i) -> new AppRecord(
            row.getString("slug"),
            row.getString("name"),
            row.getString("description"),
            row.getString("html"),
            row.getString("owner"),
            row.getInt("version"),
            Timestamps.read(row, "last_used_at"),
            row.getBoolean("archived"),
            Timestamps.read(row, "created_at"),
            Timestamps.read(row, "updated_at"),
            row.getString("updated_by"),
            Timestamps.read(row, "archived_at"),
            row.getString("archived_by"));

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final AppsProperties properties;
    private final AppOrigin appOrigin;

    public AppRegistry(JdbcClient jdbc, PlatformTransactionManager transactionManager, AppsProperties properties,
                       AppOrigin appOrigin) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.appOrigin = appOrigin;
    }

    /**
     * Most recently used first, without the HTML.
     */
    public List<AppSummary> list() {
        return jdbc.sql("""
                        select slug, name, description, owner, last_used_at, archived, version, archived_at, archived_by
                        from apps order by last_used_at desc""")
                .query((row, i) -> new AppSummary(row.getString("slug"), row.getString("name"),
                        row.getString("description"), row.getString("owner"), Timestamps.read(row, "last_used_at"),
                        row.getBoolean("archived"), row.getInt("version"), Timestamps.read(row, "archived_at"),
                        row.getString("archived_by")))
                .list();
    }

    /**
     * Opening an app counts as using it: {@code last_used_at} moves. An archived app stays archived: it opens with
     * a Restore bar, and only {@link #restore} brings it back to the list.
     */
    public AppDetail open(String slug) {
        AppRecord app = jdbc.sql("update apps set last_used_at = ? where slug = ? returning *")
                .params(Timestamps.of(Instant.now()), slug)
                .query(APP)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "No app " + slug));
        return new AppDetail(app.summary(), app.html(), appOrigin.urlFor(slug));
    }

    public boolean exists(String slug) {
        return jdbc.sql("select exists (select 1 from apps where slug = ?)").param(slug).query(Boolean.class).single();
    }

    /** The app's current version number. */
    public int version(String slug) {
        return jdbc.sql("select version from apps where slug = ?").param(slug).query(Integer.class).optional()
                .orElseThrow(() -> noApp(slug));
    }

    /** The HTML of an earlier version, while {@code app_versions} still keeps it. */
    public Optional<String> html(String slug, int version) {
        return jdbc.sql("select html from app_versions where app_slug = ? and version = ?")
                .params(slug, version)
                .query(String.class)
                .optional();
    }

    /**
     * The current HTML only, for serving the app document. Reading it is not using the app.
     */
    public String html(String slug) {
        return jdbc.sql("select html from apps where slug = ?")
                .param(slug)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "No app " + slug));
    }

    /**
     * Upserts the app and records the version in one transaction. The upsert locks the app's row, so two
     * deploys of the same app run one after the other and get consecutive versions.
     */
    public AppSummary deploy(DeployRequest request, String caller) {
        validate(request);
        String slug = slugify(request.name());
        try {
            return transaction.execute(status -> write(slug, request, caller));
        } catch (DuplicateKeyException e) {
            // Cannot happen while the row lock orders deploys; kept so a surprise is a retry, not a 500.
            throw new ResponseStatusException(CONFLICT, "Someone else is deploying this app, try again");
        }
    }

    private AppSummary write(String slug, DeployRequest request, String caller) {
        OffsetDateTime now = Timestamps.of(Instant.now());
        String name = request.name().strip();
        AppRecord app = jdbc.sql("""
                        insert into apps (slug, name, description, html, owner, version, last_used_at, archived,
                                          created_at, updated_at, updated_by)
                        values (:slug, :name, :description, :html, :caller, 1, :now, false, :now, :now, :caller)
                        on conflict (slug) do update set
                            name = excluded.name,
                            description = excluded.description,
                            html = excluded.html,
                            version = apps.version + 1,
                            last_used_at = excluded.last_used_at,
                            archived = false,
                            archived_at = null,
                            archived_by = null,
                            updated_at = excluded.updated_at,
                            updated_by = excluded.updated_by
                        returning *""")
                .param("slug", slug)
                .param("name", name)
                .param("description", request.description())
                .param("html", request.html())
                .param("caller", caller)
                .param("now", now)
                .query(APP)
                .single();

        insertVersion(app, caller, now);
        return app.summary();
    }

    /** Hides the app from the list; its HTML, versions and data stay. Archiving twice keeps the first who and when. */
    public AppSummary archive(String slug, String caller) {
        return jdbc.sql("""
                        update apps set archived = true,
                                        archived_at = coalesce(archived_at, ?),
                                        archived_by = coalesce(archived_by, ?)
                        where slug = ? returning *""")
                .params(Timestamps.of(Instant.now()), caller, slug)
                .query(APP)
                .optional()
                .orElseThrow(() -> noApp(slug))
                .summary();
    }

    public AppSummary restore(String slug) {
        return jdbc.sql("update apps set archived = false, archived_at = null, archived_by = null where slug = ? returning *")
                .param(slug)
                .query(APP)
                .optional()
                .orElseThrow(() -> noApp(slug))
                .summary();
    }

    /**
     * A new version of an existing app with new HTML. Name, description, archive state and the app's data stay. A
     * call of its own, so replacing can never create a new app from a mistyped name.
     */
    public AppSummary replaceHtml(String slug, String html, String caller) {
        validateHtml(html);
        return transaction.execute(status -> {
            OffsetDateTime now = Timestamps.of(Instant.now());
            AppRecord app = jdbc.sql("""
                            update apps set html = ?, version = version + 1, updated_at = ?, updated_by = ?
                            where slug = ? returning *""")
                    .params(html, now, caller, slug)
                    .query(APP)
                    .optional()
                    .orElseThrow(() -> noApp(slug));
            insertVersion(app, caller, now);
            return app.summary();
        });
    }

    public AppUsage usage(String slug) {
        if (!exists(slug)) {
            throw noApp(slug);
        }
        return jdbc.sql("""
                        select (select count(*) from docs where space = :slug) as documents,
                               (select count(distinct collection) from docs where space = :slug) as collections,
                               (select count(*) from app_versions where app_slug = :slug) as versions""")
                .param("slug", slug)
                .query((row, i) -> new AppUsage(row.getLong("documents"), row.getLong("collections"),
                        row.getLong("versions")))
                .single();
    }

    /**
     * Deletes the app with everything it holds: versions and documents (through the foreign keys) and the
     * documents' history (which has no foreign key, so it would otherwise outlive the app). {@code confirm} must
     * repeat the slug, so a stray request cannot wipe an app.
     */
    public DeleteResult delete(String slug, String confirm, String caller) {
        if (!slug.equals(confirm)) {
            throw new ResponseStatusException(BAD_REQUEST, "To delete an app with its data, confirm with its slug");
        }
        DeleteResult result = transaction.execute(status -> {
            AppUsage usage = usage(slug);
            long history = jdbc.sql("delete from doc_history where space = ?").param(slug).update();
            jdbc.sql("delete from apps where slug = ?").param(slug).update();
            return new DeleteResult(usage.documents(), usage.versions(), history);
        });
        log.info("App {} deleted with its data by {}: {} documents, {} versions, {} history entries",
                slug, caller, result.documents(), result.versions(), result.history());
        return result;
    }

    private void insertVersion(AppRecord app, String author, OffsetDateTime at) {
        jdbc.sql("""
                        insert into app_versions (app_slug, version, name, description, html, author, created_at)
                        values (?, ?, ?, ?, ?, ?, ?)""")
                .params(app.slug(), app.version(), app.name(), app.description(), app.html(), author, at)
                .update();
    }

    private static ResponseStatusException noApp(String slug) {
        return new ResponseStatusException(NOT_FOUND, "No app " + slug);
    }

    private void validate(DeployRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Name is required");
        }
        validateHtml(request.html());
    }

    private void validateHtml(String html) {
        if (html == null || html.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "HTML is required");
        }
        long size = html.getBytes(StandardCharsets.UTF_8).length;
        if (size > properties.maxHtmlSize().toBytes()) {
            throw new ResponseStatusException(PAYLOAD_TOO_LARGE,
                    "App is larger than " + properties.maxHtmlSize().toMegabytes() + " MB");
        }
    }

    static String slugify(String name) {
        String ascii = Normalizer.normalize(name.replace('ł', 'l').replace('Ł', 'L'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        String slug = ascii.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "Name must contain letters or digits");
        }
        if (PortFence.RESERVED_SEGMENTS.contains(slug)) {
            throw new ResponseStatusException(BAD_REQUEST, "\"" + name.strip() + "\" is reserved, choose another name");
        }
        return slug;
    }
}
