package com.drzewiecki.shitcloud.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validation and the shape of the SQL. What the SQL means on real data is in {@code FilterSqlIT}.
 */
class FilterTranslatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void appInput_isNeverPartOfTheSqlText() {
        String evil = "x'); drop table docs; --";

        FilterTranslator.Sql sql = where("{\"name\": \"" + evil.replace("\"", "\\\"") + "\", "
                + "\"n\": {\"$in\": [\"" + evil + "\"], \"$gte\": \"" + evil + "\"}}");

        assertThat(sql.text()).doesNotContain("drop").doesNotContain(evil);
        assertThat(sql.params()).anySatisfy(p -> assertThat(String.valueOf(p)).contains("drop table"));
    }

    @Test
    void fieldPaths_areBoundAsTextArrays() {
        FilterTranslator.Sql sql = where("{\"address.city\": \"Kraków\"}");

        assertThat(sql.text()).contains("data #> ?::text[]").doesNotContain("address");
        assertThat(sql.params()).contains("{address,city}", "\"Kraków\"");
    }

    @Test
    void reservedNames_useTheColumns() {
        assertThat(where("{\"id\": \"x\"}").text()).contains("to_jsonb(doc_id)");
        assertThat(where("{\"_updatedBy\": \"anna\"}").text()).contains("to_jsonb(updated_by)");
        assertThat(where("{\"_updatedAt\": {\"$gt\": \"2026-01-01T00:00:00Z\"}}").text())
                .isEqualTo("((updated_at > ?::timestamptz))");
    }

    @Test
    void everyWhitelistedOperator_isAccepted() {
        where("{\"a\": {\"$eq\": 1, \"$ne\": 2, \"$gt\": 0, \"$gte\": 0, \"$lt\": 9, \"$lte\": 9, "
                + "\"$in\": [1, 2], \"$nin\": [3], \"$exists\": true}}");
    }

    @Test
    void placeholdersAndParameters_alwaysAgree() {
        FilterTranslator.Sql sql = where("{\"$or\": [{\"a\": 1}, {\"$and\": [{\"b.c\": {\"$ne\": null}}, "
                + "{\"d\": {\"$in\": [\"x\"], \"$lt\": 3}}]}], \"id\": {\"$gt\": \"k\"}}");

        assertThat(sql.text().chars().filter(c -> c == '?').count()).isEqualTo(sql.params().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"$where\": \"sleep(1000)\"}",
            "{\"$expr\": {\"$eq\": [1, 1]}}",
            "{\"$text\": {\"$search\": \"x\"}}",
            "{\"$nor\": [{\"a\": 1}]}",
            "{\"a\": {\"$regex\": \".*\"}}",
            "{\"a\": {\"$where\": \"1\"}}",
            "{\"a\": {\"$function\": {}}}",
            "{\"a\": {\"$elemMatch\": {\"b\": 1}}}",
            "{\"a\": {\"$gt\": 1, \"b\": 2}}",
            "{\"a\": {\"$eq\": {\"$where\": \"1\"}}}",
            "{\"a\": {\"$in\": [{\"$gt\": 1}]}}",
            "{\"a\": {\"b\": {\"$gt\": 1}}}",
            "{\"a\": {\"$gt\": {\"x\": 1}}}",
            "{\"a\": {\"$gt\": null}}",
            "{\"_updatedAt\": {\"$in\": [\"2026-01-01T00:00:00Z\"]}}",
            "{\"_updatedAt\": {\"$gt\": \"yesterday\"}}",
            "{\"_updatedAt\": {\"$gt\": 5}}",
            "{\"$or\": []}",
            "{\"$or\": {\"a\": 1}}",
            "{\"$or\": [1]}",
            "{\"a\": {\"$exists\": 1}}",
            "{\"a\": {\"$in\": 1}}",
            "{\"_secret\": 1}",
            "{\"$x\": 1}",
            "{\"a..b\": 1}",
            "{\"a b\": 1}",
            "{\"a'); drop table docs; --\": 1}",
            "{\"\": 1}"
    })
    void anythingElse_isRefused(String filter) {
        assertThatThrownBy(() -> where(filter))
                .isInstanceOfSatisfying(DataException.class,
                        e -> assertThat(e.code()).isEqualTo(DataException.BAD_REQUEST));
    }

    @Test
    void deepNesting_isRefused() {
        String filter = "{\"a\": 1}";
        for (int i = 0; i < FilterTranslator.MAX_DEPTH; i++) {
            filter = "{\"$and\": [" + filter + "]}";
        }
        String tooDeep = filter;

        assertThatThrownBy(() -> where(tooDeep)).isInstanceOf(DataException.class);
    }

    @Test
    void longInLists_areRefused() {
        String values = "1,".repeat(FilterTranslator.MAX_LIST) + "1";

        assertThatThrownBy(() -> where("{\"a\": {\"$in\": [" + values + "]}}")).isInstanceOf(DataException.class);
    }

    @Test
    void noFilter_matchesEverything() {
        assertThat(FilterTranslator.where(null).text()).isEqualTo("true");
        assertThat(where("{}").text()).isEqualTo("true");
        assertThat(where("{\"$and\": [{}, {\"a\": 1}]}").text()).startsWith("((true AND");
    }

    @Test
    void sort_keepsTheAppsOrder() {
        FilterTranslator.Sql sort = FilterTranslator.orderBy(map("{\"date\": 1, \"_updatedAt\": -1}"));

        assertThat(sort.text()).endsWith("updated_at DESC").contains(" ASC");
        assertThat(sort.params()).containsOnly("{date}");
    }

    @Test
    void noSort_isEmpty() {
        assertThat(FilterTranslator.orderBy(null).text()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"a\": 2}", "{\"a\": \"asc\"}", "{\"a\": 0.5}", "{\"_x\": 1}",
            "{\"a\": 1, \"b\": 1, \"c\": 1, \"d\": 1, \"e\": 1}"})
    void badSorts_areRefused(String sort) {
        assertThatThrownBy(() -> FilterTranslator.orderBy(map(sort))).isInstanceOf(DataException.class);
    }

    private static FilterTranslator.Sql where(String json) {
        return FilterTranslator.where(map(json));
    }

    private static Map<String, Object> map(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException(json, e);
        }
    }
}
