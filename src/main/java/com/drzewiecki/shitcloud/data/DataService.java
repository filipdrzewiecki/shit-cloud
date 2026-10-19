package com.drzewiecki.shitcloud.data;

import com.drzewiecki.shitcloud.service.AppRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoException;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicQuery;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * The Data API: every app's documents in {@code docs}, partitioned by space (the app's slug) and collection.
 * Before a document is changed or removed, its previous state goes to {@code _history} in the same
 * transaction, so a write never happens without its history entry.
 *
 * <p>The space is trusted as given: it comes from the host page, which takes it from the app it shows, never
 * from the app's message. It must name an existing app.
 */
@Service
public class DataService {

    private static final Pattern COLLECTION = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern DOC_ID = Pattern.compile("[A-Za-z0-9_.:@-]{1,128}");
    private static final int MAX_DATA_DEPTH = 32;
    private static final int ATTEMPTS = 5;
    private static final int WRITE_CONFLICT = 112;

    private final MongoTemplate mongo;
    private final TransactionTemplate transaction;
    private final AppRegistry appRegistry;
    private final DataProperties properties;
    private final ObjectMapper json;

    public DataService(MongoTemplate mongo, MongoTransactionManager transactionManager, AppRegistry appRegistry,
                       DataProperties properties, ObjectMapper json) {
        this.mongo = mongo;
        this.transaction = new TransactionTemplate(transactionManager);
        this.appRegistry = appRegistry;
        this.properties = properties;
        this.json = json;
    }

    public List<DocView> query(String space, String collection, DataQuery request) {
        requireCollection(space, collection);
        DataQuery query = request == null ? new DataQuery(null, null, null) : request;

        Document filter = FilterTranslator.filter(query.filter())
                .append("space", space)
                .append("collection", collection);
        Document sort = FilterTranslator.sort(query.sort());
        if (sort.isEmpty()) {
            sort.append("createdAt", 1);
        }
        // Ties broken by insertion order, so paging through equal values is stable.
        sort.putIfAbsent("_id", 1);

        BasicQuery mongoQuery = new BasicQuery(filter);
        mongoQuery.setSortObject(sort);
        mongoQuery.limit(limit(query.limit()));
        return mongo.find(mongoQuery, DocRecord.class).stream().map(DocView::of).toList();
    }

    /**
     * Creates or replaces a document. Without {@code expectedVersion} the last write wins: losing a race with
     * another writer just means trying again, and every replaced state is still kept in history.
     */
    public DocView set(String space, String collection, String docId, DataWrite write, String caller) {
        requireCollection(space, collection);
        requireDocId(docId);
        Map<String, Object> data = cleanData(write == null ? null : write.data());
        Long expected = write.expectedVersion();
        return withRetries(expected, () -> transaction.execute(status ->
                setOnce(space, collection, docId, data, expected, caller)));
    }

    /** Creates a document under a new id that sorts by creation time. */
    public DocView add(String space, String collection, DataWrite write, String caller) {
        requireCollection(space, collection);
        Map<String, Object> data = cleanData(write == null ? null : write.data());
        Instant now = Instant.now();
        DocRecord doc = new DocRecord(null, space, collection, new ObjectId().toHexString(), data, 1,
                caller, now, caller, now);
        return DocView.of(mongo.insert(doc));
    }

    /** Removes a document; its last state stays in history. False when there was nothing to remove. */
    public boolean remove(String space, String collection, String docId, Long expectedVersion, String caller) {
        requireCollection(space, collection);
        requireDocId(docId);
        return withRetries(expectedVersion, () -> transaction.execute(status ->
                removeOnce(space, collection, docId, expectedVersion, caller)));
    }

    private DocView setOnce(String space, String collection, String docId, Map<String, Object> data,
                            Long expected, String caller) {
        Instant now = Instant.now();
        DocRecord current = mongo.findOne(byDocId(space, collection, docId), DocRecord.class);
        if (current == null) {
            requireVersion(expected, 0);
            DocRecord doc = new DocRecord(null, space, collection, docId, data, 1, caller, now, caller, now);
            return DocView.of(mongo.insert(doc));
        }
        requireVersion(expected, current.version());

        mongo.insert(HistoryRecord.of(current, "update", caller, now));
        Update update = new Update()
                .set("data", data)
                .set("version", current.version() + 1)
                .set("updatedBy", caller)
                .set("updatedAt", now);
        UpdateResult result = mongo.updateFirst(sameVersion(current), update, DocRecord.class);
        if (result.getMatchedCount() == 0) {
            throw new LostRace();
        }
        return DocView.of(new DocRecord(current.id(), space, collection, docId, data, current.version() + 1,
                current.createdBy(), current.createdAt(), caller, now));
    }

    private boolean removeOnce(String space, String collection, String docId, Long expected, String caller) {
        DocRecord current = mongo.findOne(byDocId(space, collection, docId), DocRecord.class);
        if (current == null) {
            requireVersion(expected, 0);
            return false;
        }
        requireVersion(expected, current.version());

        mongo.insert(HistoryRecord.of(current, "delete", caller, Instant.now()));
        DeleteResult result = mongo.remove(sameVersion(current), DocRecord.class);
        if (result.getDeletedCount() == 0) {
            throw new LostRace();
        }
        return true;
    }

    /**
     * Runs a write until it does not collide with a concurrent one. A collision surfaces as a write conflict or
     * transient transaction error (both writers in a transaction), a duplicate key (both inserting) or a
     * version-conditional write matching nothing. With an expected version a collision is final.
     */
    private <T> T withRetries(Long expectedVersion, Supplier<T> write) {
        for (int attempt = 1; ; attempt++) {
            try {
                return write.get();
            } catch (LostRace | DataAccessException e) {
                if (e instanceof DataAccessException dataAccess && !isConflict(dataAccess)) {
                    throw dataAccess;
                }
                if (expectedVersion != null) {
                    throw versionConflict();
                }
                if (attempt == ATTEMPTS) {
                    throw new DataException(HttpStatus.CONFLICT, DataException.CONFLICT,
                            "Too many people are changing this document right now, try again");
                }
                backOff(attempt);
            }
        }
    }

    /** Writers that collided would collide again if they retried in step; a random pause splits them up. */
    private static void backOff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(5, 20) * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataException(HttpStatus.CONFLICT, DataException.CONFLICT, "Interrupted, try again");
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

    private static void requireVersion(Long expected, long actual) {
        if (expected != null && expected != actual) {
            throw versionConflict();
        }
    }

    private static DataException versionConflict() {
        return new DataException(HttpStatus.CONFLICT, DataException.VERSION_CONFLICT,
                "The document was changed by someone else; read it again");
    }

    private int limit(Integer requested) {
        if (requested == null) {
            return properties.maxResults();
        }
        if (requested < 1 || requested > properties.maxResults()) {
            throw DataException.badRequest("limit must be between 1 and " + properties.maxResults());
        }
        return requested;
    }

    private void requireCollection(String space, String collection) {
        if (!COLLECTION.matcher(collection).matches()) {
            throw DataException.badRequest("Collection names are 1-64 letters, digits, _ or -");
        }
        if (!appRegistry.exists(space)) {
            throw new DataException(HttpStatus.NOT_FOUND, DataException.NO_SUCH_APP, "No app " + space);
        }
    }

    private static void requireDocId(String docId) {
        if (!DOC_ID.matcher(docId).matches()) {
            throw DataException.badRequest("Ids are 1-128 letters, digits or _ . : @ -");
        }
    }

    /**
     * The fields to store. {@code id} and fields starting with {@code _} are what reads add to a document, so an
     * app that writes back what it read just works: they are dropped, not refused.
     */
    private Map<String, Object> cleanData(Map<String, Object> data) {
        if (data == null) {
            throw DataException.badRequest("data must be an object");
        }
        Map<String, Object> clean = new LinkedHashMap<>();
        data.forEach((key, value) -> {
            if (!key.equals("id") && !key.startsWith("_")) {
                clean.put(key, value);
            }
        });
        checkNames(clean, 1);
        long size = jsonSize(clean);
        if (size > properties.maxDocSize().toBytes()) {
            throw new DataException(HttpStatus.PAYLOAD_TOO_LARGE, DataException.TOO_LARGE,
                    "A document can be at most " + properties.maxDocSize().toKilobytes() + " KB");
        }
        return clean;
    }

    /** Mongo cannot query by names with dots or a leading $, so they are refused at any depth. */
    private static void checkNames(Object value, int depth) {
        if (depth > MAX_DATA_DEPTH) {
            throw DataException.badRequest("data is nested deeper than " + MAX_DATA_DEPTH + " levels");
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, nested) -> {
                String name = String.valueOf(key);
                if (name.isEmpty() || name.contains(".") || name.startsWith("$")) {
                    throw DataException.badRequest("'" + name + "' is not a valid field name");
                }
                checkNames(nested, depth + 1);
            });
        } else if (value instanceof List<?> list) {
            list.forEach(item -> checkNames(item, depth + 1));
        }
    }

    private long jsonSize(Map<String, Object> data) {
        try {
            return json.writeValueAsBytes(data).length;
        } catch (JsonProcessingException e) {
            throw DataException.badRequest("data cannot be stored: " + e.getOriginalMessage());
        }
    }

    private static Query byDocId(String space, String collection, String docId) {
        return new Query(where("space").is(space).and("collection").is(collection).and("docId").is(docId));
    }

    private static Query sameVersion(DocRecord doc) {
        return new Query(where("_id").is(new ObjectId(doc.id())).and("version").is(doc.version()));
    }

    /** A version-conditional write found the document already changed by someone else. */
    private static final class LostRace extends RuntimeException {
        LostRace() {
            super(null, null, false, false);
        }
    }
}
