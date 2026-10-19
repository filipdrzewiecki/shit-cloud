package com.drzewiecki.shitcloud.service;

import com.drzewiecki.shitcloud.apps.AppOrigin;
import com.drzewiecki.shitcloud.apps.PortFence;
import com.drzewiecki.shitcloud.model.AppDetail;
import com.drzewiecki.shitcloud.model.AppRecord;
import com.drzewiecki.shitcloud.model.AppSummary;
import com.drzewiecki.shitcloud.model.AppVersion;
import com.drzewiecki.shitcloud.model.DeployRequest;
import com.drzewiecki.shitcloud.mongo.MongoCollections;
import com.mongodb.MongoException;
import org.bson.Document;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE;

/**
 * The app registry: {@code _apps} holds each app with its current HTML, {@code _app_versions} every
 * deploy. Apps are identified by the slug of their name, so deploying the same name again is a new version.
 */
@Service
public class AppRegistry {

    private static final int WRITE_CONFLICT = 112;

    private final MongoTemplate mongo;
    private final TransactionTemplate transaction;
    private final AppsProperties properties;
    private final AppOrigin appOrigin;

    public AppRegistry(MongoTemplate mongo, MongoTransactionManager transactionManager, AppsProperties properties,
                       AppOrigin appOrigin) {
        this.mongo = mongo;
        this.transaction = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.appOrigin = appOrigin;
    }

    /**
     * Most recently used first, without the HTML.
     */
    public List<AppSummary> list() {
        Query query = new Query().with(Sort.by(Sort.Direction.DESC, "lastUsedAt"));
        query.fields().exclude("html");
        return mongo.find(query, AppRecord.class).stream()
                .map(AppRecord::summary)
                .toList();
    }

    /**
     * Opening an app counts as using it: {@code lastUsedAt} moves and an archived app comes back.
     */
    public AppDetail open(String slug) {
        Update update = new Update()
                .set("lastUsedAt", Instant.now())
                .set("archived", false);
        AppRecord app = mongo.findAndModify(bySlug(slug), update, FindAndModifyOptions.options().returnNew(true),
                AppRecord.class);
        if (app == null) {
            throw new ResponseStatusException(NOT_FOUND, "No app " + slug);
        }
        return new AppDetail(app.summary(), app.html(), appOrigin.urlFor(slug));
    }

    public boolean exists(String slug) {
        return mongo.exists(bySlug(slug), MongoCollections.APPS);
    }

    /**
     * The current HTML only, for serving the app document. Reading it is not using the app.
     */
    public String html(String slug) {
        Query query = bySlug(slug);
        query.fields().include("html");
        // A raw document: AppRecord cannot be built from a projection (its primitives would be null).
        Document app = mongo.findOne(query, Document.class, MongoCollections.APPS);
        if (app == null) {
            throw new ResponseStatusException(NOT_FOUND, "No app " + slug);
        }
        return app.getString("html");
    }

    /**
     * Upserts the app and records the version in one transaction. The version number comes from an atomic
     * increment, so two deploys of the same app can never both become the same version: the second one
     * fails with a write conflict and the caller is told to retry.
     */
    public AppSummary deploy(DeployRequest request, String caller) {
        validate(request);
        String slug = slugify(request.name());
        try {
            return transaction.execute(status -> write(slug, request, caller));
        } catch (DataAccessException e) {
            if (isConflict(e)) {
                throw new ResponseStatusException(CONFLICT, "Someone else is deploying this app, try again");
            }
            throw e;
        }
    }

    private AppSummary write(String slug, DeployRequest request, String caller) {
        Instant now = Instant.now();
        String name = request.name().strip();
        Update update = new Update()
                .set("name", name)
                .set("description", request.description())
                .set("html", request.html())
                .set("lastUsedAt", now)
                .set("archived", false)
                .set("updatedAt", now)
                .set("updatedBy", caller)
                .inc("version", 1)
                .setOnInsert("owner", caller)
                .setOnInsert("createdAt", now);
        FindAndModifyOptions upsert = FindAndModifyOptions.options().upsert(true).returnNew(true);
        AppRecord app = mongo.findAndModify(bySlug(slug), update, upsert, AppRecord.class);

        mongo.insert(new AppVersion(null, slug, app.version(), name, request.description(), request.html(),
                caller, now));
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

    private static boolean isConflict(DataAccessException e) {
        if (e instanceof DuplicateKeyException) {
            return true;
        }
        return e.getMostSpecificCause() instanceof MongoException mongoException
                && (mongoException.getCode() == WRITE_CONFLICT
                || mongoException.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL));
    }

    private static Query bySlug(String slug) {
        return new Query(where("_id").is(slug));
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
