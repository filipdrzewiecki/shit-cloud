package com.drzewiecki.shitcloud.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilterTranslatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void implicitEquality_movesTheFieldUnderData() {
        assertThat(filter("{\"userId\": \"anna\", \"done\": true}"))
                .isEqualTo(doc("{\"data.userId\": \"anna\", \"data.done\": true}"));
    }

    @Test
    void comparisonOperators_arePassedOn() {
        assertThat(filter("{\"date\": {\"$gte\": \"2026-10-01\", \"$lt\": \"2026-11-01\"}, \"n\": {\"$ne\": 3}}"))
                .isEqualTo(doc("{\"data.date\": {\"$gte\": \"2026-10-01\", \"$lt\": \"2026-11-01\"}, "
                        + "\"data.n\": {\"$ne\": 3}}"));
    }

    @Test
    void everyWhitelistedOperator_isAccepted() {
        filter("{\"a\": {\"$eq\": 1, \"$ne\": 2, \"$gt\": 0, \"$gte\": 0, \"$lt\": 9, \"$lte\": 9, "
                + "\"$in\": [1, 2], \"$nin\": [3], \"$exists\": true}}");
    }

    @Test
    void andOr_areTranslatedAtAnyLevel() {
        assertThat(filter("{\"$or\": [{\"a\": 1}, {\"$and\": [{\"b\": 2}, {\"c\": {\"$gt\": 3}}]}]}"))
                .isEqualTo(doc("{\"$or\": [{\"data.a\": 1}, {\"$and\": [{\"data.b\": 2}, {\"data.c\": {\"$gt\": 3}}]}]}"));
    }

    @Test
    void dottedPaths_reachNestedFields() {
        assertThat(filter("{\"address.city\": \"Kraków\"}")).isEqualTo(doc("{\"data.address.city\": \"Kraków\"}"));
    }

    @Test
    void platformFieldsAsTheSdkNamesThem_areMapped() {
        assertThat(filter("{\"id\": \"x\", \"_updatedBy\": \"anna\", \"_updatedAt\": {\"$gt\": \"2026\"}}"))
                .isEqualTo(doc("{\"docId\": \"x\", \"updatedBy\": \"anna\", \"updatedAt\": {\"$gt\": \"2026\"}}"));
    }

    @Test
    void anObjectWithoutOperators_isAnExactMatch() {
        assertThat(filter("{\"slot\": {\"day\": 1, \"hour\": 9}}"))
                .isEqualTo(doc("{\"data.slot\": {\"day\": 1, \"hour\": 9}}"));
    }

    @Test
    void platformFields_cannotBeReached() {
        // "space" is an app field like any other: it can only ever mean data.space.
        assertThat(filter("{\"space\": \"other-app\"}")).isEqualTo(doc("{\"data.space\": \"other-app\"}"));
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
            "{\"$or\": []}",
            "{\"$or\": {\"a\": 1}}",
            "{\"$or\": [1]}",
            "{\"a\": {\"$exists\": 1}}",
            "{\"a\": {\"$in\": 1}}",
            "{\"_secret\": 1}",
            "{\"$x\": 1}",
            "{\"a..b\": 1}",
            "{\"a b\": 1}",
            "{\"\": 1}"
    })
    void anythingElse_isRefused(String filter) {
        assertThatThrownBy(() -> filter(filter))
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

        assertThatThrownBy(() -> filter(tooDeep)).isInstanceOf(DataException.class);
    }

    @Test
    void longInLists_areRefused() {
        String values = "1,".repeat(FilterTranslator.MAX_LIST) + "1";

        assertThatThrownBy(() -> filter("{\"a\": {\"$in\": [" + values + "]}}")).isInstanceOf(DataException.class);
    }

    @Test
    void noFilter_isEmpty() {
        assertThat(FilterTranslator.filter(null)).isEmpty();
    }

    @Test
    void sort_mapsFieldsAndKeepsOrder() {
        assertThat(FilterTranslator.sort(map("{\"date\": 1, \"_updatedAt\": -1}")))
                .isEqualTo(doc("{\"data.date\": 1, \"updatedAt\": -1}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"a\": 2}", "{\"a\": \"asc\"}", "{\"a\": 0.5}", "{\"_x\": 1}",
            "{\"a\": 1, \"b\": 1, \"c\": 1, \"d\": 1, \"e\": 1}"})
    void badSorts_areRefused(String sort) {
        assertThatThrownBy(() -> FilterTranslator.sort(map(sort))).isInstanceOf(DataException.class);
    }

    private static Document filter(String json) {
        return FilterTranslator.filter(map(json));
    }

    private static Document doc(String json) {
        return Document.parse(json);
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
