package com.drzewiecki.shitcloud.data;

import com.drzewiecki.shitcloud.IntegrationTest;
import com.drzewiecki.shitcloud.model.DeployRequest;
import com.drzewiecki.shitcloud.service.AppRegistry;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What app filters mean on real PostgreSQL: the answers apps got from MongoDB, now from JSONB.
 */
class FilterSqlIT extends IntegrationTest {

    @Autowired
    private DataService data;

    @Autowired
    private AppRegistry appRegistry;

    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void fixtures() {
        appRegistry.deploy(new DeployRequest("Filtry", null, "<p>x</p>"), BUILDER_EMAIL);
        put("a", "{\"n\": 1, \"s\": \"apple\", \"tags\": [\"x\", \"y\"], \"nested\": {\"city\": \"Kraków\"}, "
                + "\"flag\": true, \"nothing\": null}");
        put("b", "{\"n\": 2, \"s\": \"Banana\", \"tags\": [\"y\"], \"nested\": {\"city\": \"Gdańsk\"}, \"flag\": false}");
        put("c", "{\"n\": \"3\", \"s\": \"cherry\"}");
        put("d", "{\"s\": \"date\"}");
        put("e", "{\"n\": 10, \"s\": \"elder\", \"tags\": \"x\"}");
    }

    @Test
    void equality() {
        assertThat(ids("{\"n\": 1}")).containsExactly("a");
        assertThat(ids("{\"s\": \"apple\"}")).containsExactly("a");
        assertThat(ids("{\"n\": \"3\"}")).containsExactly("c");
        assertThat(ids("{\"n\": 1.0}")).containsExactly("a");
        assertThat(ids("{\"nested\": {\"city\": \"Gdańsk\"}}")).containsExactly("b");
    }

    @Test
    void equalityOnAnArray_matchesArraysContainingTheValue() {
        assertThat(ids("{\"tags\": \"x\"}")).containsExactly("a", "e");
        assertThat(ids("{\"tags\": [\"y\"]}")).containsExactly("b");
    }

    @Test
    void nestedPaths() {
        assertThat(ids("{\"nested.city\": \"Kraków\"}")).containsExactly("a");
    }

    @Test
    void notEqual_alsoMatchesMissingFields() {
        assertThat(ids("{\"n\": {\"$ne\": 1}}")).containsExactly("b", "c", "d", "e");
        assertThat(ids("{\"tags\": {\"$ne\": \"x\"}}")).containsExactly("b", "c", "d");
    }

    @Test
    void ranges_compareOneTypeOnly() {
        // "3" is text, so it is not greater than 1; d has no n at all.
        assertThat(ids("{\"n\": {\"$gt\": 1}}")).containsExactly("b", "e");
        assertThat(ids("{\"n\": {\"$gte\": 2, \"$lt\": 10}}")).containsExactly("b");
        assertThat(ids("{\"n\": {\"$lt\": \"9\"}}")).containsExactly("c");
        assertThat(ids("{\"flag\": {\"$gt\": false}}")).containsExactly("a");
    }

    @Test
    void textRanges_compareByCodePoint() {
        // "Banana" starts with an upper-case letter, which sorts before every lower-case one.
        assertThat(ids("{\"s\": {\"$gte\": \"b\"}}")).containsExactly("c", "d", "e");
        assertThat(ids("{\"s\": {\"$lt\": \"a\"}}")).containsExactly("b");
    }

    @Test
    void inAndNotIn() {
        assertThat(ids("{\"n\": {\"$in\": [1, \"3\"]}}")).containsExactly("a", "c");
        assertThat(ids("{\"tags\": {\"$in\": [\"y\"]}}")).containsExactly("a", "b");
        assertThat(ids("{\"n\": {\"$nin\": [1, 2]}}")).containsExactly("c", "d", "e");
        assertThat(ids("{\"n\": {\"$in\": []}}")).isEmpty();
    }

    @Test
    void existsAndNull() {
        assertThat(ids("{\"n\": {\"$exists\": false}}")).containsExactly("d");
        assertThat(ids("{\"nothing\": {\"$exists\": true}}")).containsExactly("a");
        // As in MongoDB, null matches a null value and a missing field.
        assertThat(ids("{\"nothing\": null}")).containsExactly("a", "b", "c", "d", "e");
        assertThat(ids("{\"tags\": null}")).containsExactly("c", "d");
        assertThat(ids("{\"tags\": {\"$ne\": null}}")).containsExactly("a", "b", "e");
    }

    @Test
    void andOr() {
        assertThat(ids("{\"$or\": [{\"n\": 1}, {\"s\": \"date\"}]}")).containsExactly("a", "d");
        assertThat(ids("{\"$and\": [{\"$or\": [{\"n\": 1}, {\"n\": 2}]}, {\"flag\": false}]}")).containsExactly("b");
        assertThat(ids("{\"$and\": [{}, {\"n\": 2}]}")).containsExactly("b");
    }

    @Test
    void platformFields() {
        assertThat(ids("{\"id\": {\"$gt\": \"b\"}}")).containsExactly("c", "d", "e");
        assertThat(ids("{\"_updatedBy\": \"guest:Anna\"}")).containsExactly("a", "b", "c", "d", "e");
        assertThat(ids("{\"_updatedAt\": {\"$gte\": \"2000-01-01T00:00:00Z\"}}")).hasSize(5);
        assertThat(ids("{\"_updatedAt\": {\"$lt\": \"2000-01-01T00:00:00Z\"}}")).isEmpty();
    }

    @Test
    void sort_followsMongoTypeOrder_missingFirst() {
        assertThat(sorted("{\"n\": 1}")).containsExactly("d", "a", "b", "e", "c");
        assertThat(sorted("{\"n\": -1}")).containsExactly("c", "e", "b", "a", "d");
        assertThat(sorted("{\"s\": 1}")).containsExactly("b", "a", "c", "d", "e");
        assertThat(sorted("{\"id\": -1}")).containsExactly("e", "d", "c", "b", "a");
    }

    @Test
    void pagingById_walksThroughEverything() {
        List<DocView> first = data.query("filtry", "things", new DataQuery(null, map("{\"id\": 1}"), 2));
        List<DocView> second = data.query("filtry", "things",
                new DataQuery(map("{\"id\": {\"$gt\": \"" + first.get(1).id() + "\"}}"), map("{\"id\": 1}"), 2));

        assertThat(first).extracting(DocView::id).containsExactly("a", "b");
        assertThat(second).extracting(DocView::id).containsExactly("c", "d");
    }

    @Test
    void injectionAttemptsInValues_areJustValues() {
        assertThat(ids("{\"s\": \"x'); drop table docs; --\"}")).isEmpty();
        assertThat(ids("{\"s\": {\"$in\": [\"'; delete from docs; --\"]}}")).isEmpty();
        assertThat(ids("{}")).hasSize(5);
    }

    private void put(String id, String fields) {
        data.set("filtry", "things", id, new DataWrite(map(fields), null), "guest:Anna");
    }

    private List<String> ids(String filter) {
        return data.query("filtry", "things", new DataQuery(map(filter), map("{\"id\": 1}"), null))
                .stream().map(DocView::id).toList();
    }

    private List<String> sorted(String sort) {
        return data.query("filtry", "things", new DataQuery(null, map(sort), null))
                .stream().map(DocView::id).toList();
    }

    private Map<String, Object> map(String text) {
        try {
            return json.readValue(text, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException(text, e);
        }
    }
}
