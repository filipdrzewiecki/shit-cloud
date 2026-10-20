package com.drzewiecki.shitcloud.service;

import com.drzewiecki.shitcloud.apps.AppOrigin;
import com.drzewiecki.shitcloud.apps.PortFence;
import com.drzewiecki.shitcloud.db.Timestamps;
import com.drzewiecki.shitcloud.model.AppDetail;
import com.drzewiecki.shitcloud.model.AppRecord;
import com.drzewiecki.shitcloud.model.AppSummary;
import com.drzewiecki.shitcloud.model.DeployRequest;
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
import java.util.List;

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
            row.getString("updated_by"));

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
                        select slug, name, description, owner, last_used_at, archived, version
                        from apps order by last_used_at desc""")
                .query((row, i) -> new AppSummary(row.getString("slug"), row.getString("name"),
                        row.getString("description"), row.getString("owner"), Timestamps.read(row, "last_used_at"),
                        row.getBoolean("archived"), row.getInt("version")))
                .list();
    }

    /**
     * Opening an app counts as using it: {@code last_used_at} moves and an archived app comes back.
     */
    public AppDetail open(String slug) {
        AppRecord app = jdbc.sql("update apps set last_used_at = ?, archived = false where slug = ? returning *")
                .params(Timestamps.of(Instant.now()), slug)
                .query(APP)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "No app " + slug));
        return new AppDetail(app.summary(), app.html(), appOrigin.urlFor(slug));
    }

    public boolean exists(String slug) {
        return jdbc.sql("select exists (select 1 from apps where slug = ?)").param(slug).query(Boolean.class).single();
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
        var now = Timestamps.of(Instant.now());
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

        jdbc.sql("""
                        insert into app_versions (app_slug, version, name, description, html, author, created_at)
                        values (?, ?, ?, ?, ?, ?, ?)""")
                .params(slug, app.version(), name, request.description(), request.html(), caller, now)
                .update();
        return app.summary();
    }

    private void validate(DeployRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Name is required");
        }
        if (request.html() == null || request.html().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "HTML is required");
        }
        long size = request.html().getBytes(StandardCharsets.UTF_8).length;
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
