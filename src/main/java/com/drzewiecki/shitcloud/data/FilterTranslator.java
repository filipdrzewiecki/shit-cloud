package com.drzewiecki.shitcloud.data;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns an app's Mongo-style filter and sort into SQL over {@code docs}. Apps keep writing the filters they always
 * did; this class gives them the same meaning on PostgreSQL JSONB.
 *
 * <p>The input is untrusted (AI-written or uploaded apps), so nothing is passed through: every key is checked
 * against a whitelist, and <b>no app input is ever part of the SQL text</b> — field paths and values are always
 * bind parameters. App fields live in the {@code data} column, which keeps {@code space} and every other platform
 * column out of an app's reach.
 *
 * <p>Allowed: implicit equality, {@code $eq $ne $gt $gte $lt $lte $in $nin $exists}, {@code $and}/{@code $or} at
 * any level, dotted paths. Everything else — {@code $where}, {@code $expr}, {@code $function}, {@code $regex}
 * (a slow pattern would stall the database for everyone) — is refused. Semantics follow MongoDB where apps rely on
 * them: {@code $ne}/{@code $nin} also match documents without the field, equality on an array field matches an
 * array containing the value, and range comparisons only match values of the same type.
 */
public final class FilterTranslator {

    static final int MAX_DEPTH = 8;
    static final int MAX_LIST = 500;
    static final int MAX_SORT_KEYS = 4;

    /** SQL with its positional parameters, in order. */
    public record Sql(String text, List<Object> params) {
        boolean isEmpty() {
            return text.isEmpty();
        }
    }

    private static final Set<String> OPERATORS =
            Set.of("$eq", "$ne", "$gt", "$gte", "$lt", "$lte", "$in", "$nin", "$exists");
    private static final Map<String, String> RANGES = Map.of("$gt", ">", "$gte", ">=", "$lt", "<", "$lte", "<=");
    private static final Set<String> LOGICAL = Set.of("$and", "$or");
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_-]+");
    private static final ObjectMapper JSON = new ObjectMapper();

    // Templates: {F} is the field's SQL (with its own parameters), each ? the next value.
    private static final String EQUALS =
            "({F} = ?::jsonb OR (jsonb_typeof({F}) = 'array' AND {F} @> jsonb_build_array(?::jsonb)))";
    private static final String IN = "EXISTS (SELECT 1 FROM jsonb_array_elements(?::jsonb) AS e(v) "
            + "WHERE {F} = e.v OR (jsonb_typeof({F}) = 'array' AND {F} @> jsonb_build_array(e.v)))";
    // CASE, not AND: SQL does not promise to check the type before the cast runs.
    private static final String STRING_RANGE =
            "CASE WHEN jsonb_typeof({F}) = 'string' THEN ({F} #>> '{}') COLLATE \"C\" %s ? ELSE false END";
    private static final String NUMBER_RANGE =
            "CASE WHEN jsonb_typeof({F}) = 'number' THEN ({F} #>> '{}')::numeric %s ? ELSE false END";
    private static final String BOOLEAN_RANGE =
            "CASE WHEN jsonb_typeof({F}) = 'boolean' THEN ({F} #>> '{}')::boolean %s ? ELSE false END";

    /** How a name the app filters on maps to the table. */
    private record Field(String sql, List<Object> params, boolean timestamp, String sortSql) {

        static Field json(String path) {
            // Bound as a text[] literal; segments are already restricted to [A-Za-z0-9_-].
            return new Field("(data #> ?::text[])", List.of("{" + path.replace('.', ',') + "}"), false, null);
        }

        static Field textColumn(String column) {
            return new Field("to_jsonb(" + column + ")", List.of(), false, column + " COLLATE \"C\"");
        }
    }

    /** Names an app may use for the platform's columns, as the SDK shows them. */
    private static final Map<String, Field> RESERVED_FIELDS = Map.of(
            "id", Field.textColumn("doc_id"),
            "_updatedBy", Field.textColumn("updated_by"),
            "_updatedAt", new Field("updated_at", List.of(), true, "updated_at"));

    private FilterTranslator() {
    }

    /** The condition on the app's fields; {@code true} when the app gave none. Space and collection are the caller's. */
    public static Sql where(Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return new Sql("true", List.of());
        }
        Builder out = new Builder();
        translate(filter, 1, out);
        return out.build();
    }

    /** ORDER BY terms for the app's sort; empty when the app gave none. */
    public static Sql orderBy(Map<String, Object> sort) {
        Builder out = new Builder();
        if (sort == null) {
            return out.build();
        }
        if (sort.size() > MAX_SORT_KEYS) {
            throw DataException.badRequest("Sort by at most " + MAX_SORT_KEYS + " fields");
        }
        sort.forEach((path, direction) -> {
            if (!(direction instanceof Number number) || Math.abs(number.intValue()) != 1
                    || number.doubleValue() != number.intValue()) {
                throw DataException.badRequest("Sort direction of '" + path + "' must be 1 or -1");
            }
            String dir = number.intValue() == 1 ? " ASC" : " DESC";
            Field field = field(path);
            out.separator(", ");
            if (field.sortSql() != null) {
                out.sql(field.sortSql() + dir);
                return;
            }
            // MongoDB's order: missing and null, then numbers, strings, objects, arrays, booleans.
            out.template("CASE jsonb_typeof({F}) WHEN 'number' THEN 1 WHEN 'string' THEN 2 WHEN 'object' THEN 3 "
                    + "WHEN 'array' THEN 4 WHEN 'boolean' THEN 5 ELSE 0 END" + dir, field);
            out.sql(", ");
            out.template("CASE WHEN jsonb_typeof({F}) = 'number' THEN ({F} #>> '{}')::numeric END" + dir, field);
            out.sql(", ");
            out.template("({F} #>> '{}') COLLATE \"C\"" + dir, field);
        });
        return out.build();
    }

    /** How an app's field path is reached; refuses anything that is not a plain path or a reserved name. */
    static Field field(String path) {
        Field reserved = RESERVED_FIELDS.get(path);
        if (reserved != null) {
            return reserved;
        }
        String[] segments = path.split("\\.", -1);
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches()) {
                throw DataException.badRequest("'" + path + "' is not a valid field name");
            }
        }
        if (segments[0].startsWith("_")) {
            throw DataException.badRequest("'" + path + "' is not a valid field name: names starting with _ "
                    + "are reserved (use id, _updatedAt or _updatedBy)");
        }
        return Field.json(path);
    }

    private static void translate(Map<?, ?> filter, int depth, Builder out) {
        requireDepth(depth);
        if (filter.isEmpty()) {
            // {} matches everything, also inside $and / $or (paging code writes { $and: [filter || {}, ...] }).
            out.sql("true");
            return;
        }
        out.sql("(");
        boolean first = true;
        for (Map.Entry<?, ?> entry : filter.entrySet()) {
            if (!first) {
                out.sql(" AND ");
            }
            first = false;
            String key = String.valueOf(entry.getKey());
            if (LOGICAL.contains(key)) {
                logical(key, entry.getValue(), depth, out);
            } else if (key.startsWith("$")) {
                throw DataException.badRequest("Operator " + key + " is not allowed here");
            } else {
                condition(key, entry.getValue(), depth, out);
            }
        }
        out.sql(")");
    }

    private static void logical(String operator, Object value, int depth, Builder out) {
        if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > MAX_LIST) {
            throw DataException.badRequest(operator + " needs a list of 1 to " + MAX_LIST + " filters");
        }
        String join = operator.equals("$and") ? " AND " : " OR ";
        out.sql("(");
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> map)) {
                throw DataException.badRequest(operator + " needs a list of filters");
            }
            if (i > 0) {
                out.sql(join);
            }
            translate(map, depth + 1, out);
        }
        out.sql(")");
    }

    private static void condition(String path, Object value, int depth, Builder out) {
        Field field = field(path);
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().noneMatch(FilterTranslator::isOperator)) {
            // A plain value or object: equality.
            operator(field, path, "$eq", value, depth, out);
            return;
        }
        out.sql("(");
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String operator = String.valueOf(entry.getKey());
            if (!OPERATORS.contains(operator)) {
                throw DataException.badRequest("Operator " + operator + " is not allowed (on '" + path + "'); "
                        + "allowed: " + String.join(" ", OPERATORS.stream().sorted().toList()));
            }
            if (!first) {
                out.sql(" AND ");
            }
            first = false;
            operator(field, path, operator, entry.getValue(), depth + 1, out);
        }
        out.sql(")");
    }

    private static void operator(Field field, String path, String operator, Object operand, int depth, Builder out) {
        if (operator.equals("$exists")) {
            if (!(operand instanceof Boolean exists)) {
                throw DataException.badRequest("$exists needs true or false");
            }
            out.template(exists ? "{F} IS NOT NULL" : "{F} IS NULL", field);
            return;
        }
        if (field.timestamp()) {
            timestampOperator(field, path, operator, operand, out);
            return;
        }
        if (operand == null && (operator.equals("$eq") || operator.equals("$ne"))) {
            // MongoDB: {a: null} matches a null value and a missing field alike.
            out.template(operator.equals("$eq") ? "({F} IS NULL OR {F} = 'null'::jsonb)"
                    : "({F} IS NOT NULL AND {F} <> 'null'::jsonb)", field);
            return;
        }
        switch (operator) {
            case "$eq" -> out.template(EQUALS, field, json(literal(operand, depth)), json(literal(operand, depth)));
            // NOT coalesce: a missing field makes the comparison NULL, and MongoDB counts it as "not equal".
            case "$ne" -> out.template("NOT coalesce(" + EQUALS + ", false)", field,
                    json(literal(operand, depth)), json(literal(operand, depth)));
            case "$in" -> out.template(IN, field, json(list(operator, operand, depth)));
            case "$nin" -> out.template("NOT " + IN, field, json(list(operator, operand, depth)));
            default -> range(field, operator, literal(operand, depth), out);
        }
    }

    /** Ranges compare values of one type only, as MongoDB does; strings by code point, not by locale. */
    private static void range(Field field, String operator, Object value, Builder out) {
        String sqlOperator = RANGES.get(operator);
        if (value instanceof String text) {
            out.template(STRING_RANGE.formatted(sqlOperator), field, text);
        } else if (value instanceof Number number) {
            out.template(NUMBER_RANGE.formatted(sqlOperator), field, new BigDecimal(number.toString()));
        } else if (value instanceof Boolean bool) {
            out.template(BOOLEAN_RANGE.formatted(sqlOperator), field, bool);
        } else {
            throw DataException.badRequest(operator + " compares text, numbers or true/false");
        }
    }

    /** {@code _updatedAt} is a real timestamp column: compared as time, from an ISO string. */
    private static void timestampOperator(Field field, String path, String operator, Object operand, Builder out) {
        if (!(operand instanceof String text)) {
            throw DataException.badRequest(path + " is compared with an ISO date-time text");
        }
        try {
            OffsetDateTime.parse(text);
        } catch (DateTimeParseException e) {
            throw DataException.badRequest("'" + text + "' is not an ISO date-time like 2026-10-20T08:00:00Z");
        }
        String sqlOperator = switch (operator) {
            case "$eq" -> "=";
            case "$ne" -> "<>";
            case "$in", "$nin" -> throw DataException.badRequest(operator + " is not supported on " + path);
            default -> RANGES.get(operator);
        };
        out.template("{F} " + sqlOperator + " ?::timestamptz", field, text);
    }

    private static List<Object> list(String operator, Object operand, int depth) {
        if (!(operand instanceof List<?> list) || list.size() > MAX_LIST) {
            throw DataException.badRequest(operator + " needs a list of at most " + MAX_LIST + " values");
        }
        return list.stream().map(item -> literal(item, depth + 1)).toList();
    }

    /** A value compared as is. It may be an object, but one without operators anywhere inside. */
    private static Object literal(Object value, int depth) {
        requireDepth(depth);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                if (isOperator(key)) {
                    throw DataException.badRequest("Operator " + key + " is not allowed inside a value");
                }
                out.put(String.valueOf(key), literal(nested, depth + 1));
            });
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> literal(item, depth + 1)).toList();
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        throw DataException.badRequest("Unsupported value " + value);
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JacksonException e) {
            throw DataException.badRequest("Unsupported value " + value);
        }
    }

    private static boolean isOperator(Object key) {
        return String.valueOf(key).startsWith("$");
    }

    private static void requireDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw DataException.badRequest("The filter is nested deeper than " + MAX_DEPTH + " levels");
        }
    }

    /** Collects SQL text and its parameters in the order the placeholders appear. */
    private static final class Builder {

        private final StringBuilder text = new StringBuilder();
        private final List<Object> params = new ArrayList<>();

        void sql(String sql) {
            text.append(sql);
        }

        void separator(String separator) {
            if (!text.isEmpty()) {
                text.append(separator);
            }
        }

        /** Expands {F} into the field's SQL and parameters, and each ? into the next of {@code values}. */
        void template(String template, Field field, Object... values) {
            int next = 0;
            for (int i = 0; i < template.length(); i++) {
                if (template.startsWith("{F}", i)) {
                    text.append(field.sql());
                    params.addAll(field.params());
                    i += 2;
                } else if (template.charAt(i) == '?') {
                    text.append('?');
                    params.add(values[next++]);
                } else {
                    text.append(template.charAt(i));
                }
            }
            if (next != values.length) {
                throw new IllegalStateException("Template and values disagree: " + template);
            }
        }

        Sql build() {
            return new Sql(text.toString(), List.copyOf(params));
        }
    }
}
