package com.drzewiecki.shitcloud.data;

import org.bson.Document;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns an app's Mongo-style filter and sort into a query on {@code docs}. The input is untrusted (AI-written or
 * uploaded apps), so nothing is passed through: every key is checked against a whitelist and the document is
 * rebuilt. App fields are moved under {@code data.}, which keeps {@code space} and every other platform field
 * out of an app's reach.
 *
 * <p>Allowed: implicit equality, {@code $eq $ne $gt $gte $lt $lte $in $nin $exists}, {@code $and}/{@code $or}
 * at any level, dotted paths. Everything else — {@code $where}, {@code $expr}, {@code $function},
 * {@code $regex} (a slow pattern would stall the database for everyone) — is refused.
 */
public final class FilterTranslator {

    static final int MAX_DEPTH = 8;
    static final int MAX_LIST = 500;
    static final int MAX_SORT_KEYS = 4;

    private static final Set<String> OPERATORS =
            Set.of("$eq", "$ne", "$gt", "$gte", "$lt", "$lte", "$in", "$nin", "$exists");
    private static final Set<String> LOGICAL = Set.of("$and", "$or");
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_-]+");
    /** Names an app may use for the platform's fields, as the SDK shows them. */
    private static final Map<String, String> RESERVED_FIELDS =
            Map.of("id", "docId", "_updatedAt", "updatedAt", "_updatedBy", "updatedBy");

    private FilterTranslator() {
    }

    /** The filter on the app's fields; empty when the app gave none. Space and collection are added by the caller. */
    public static Document filter(Map<String, Object> filter) {
        return filter == null ? new Document() : translate(filter, 1);
    }

    /** The sort on the app's fields; empty when the app gave none. */
    public static Document sort(Map<String, Object> sort) {
        Document out = new Document();
        if (sort == null) {
            return out;
        }
        if (sort.size() > MAX_SORT_KEYS) {
            throw DataException.badRequest("Sort by at most " + MAX_SORT_KEYS + " fields");
        }
        sort.forEach((path, direction) -> {
            if (!(direction instanceof Number number) || Math.abs(number.intValue()) != 1
                    || number.doubleValue() != number.intValue()) {
                throw DataException.badRequest("Sort direction of '" + path + "' must be 1 or -1");
            }
            out.append(field(path), number.intValue());
        });
        return out;
    }

    /** Where an app's field path lives in {@code docs}. */
    static String field(String path) {
        String reserved = RESERVED_FIELDS.get(path);
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
        return "data." + path;
    }

    private static Document translate(Map<?, ?> filter, int depth) {
        requireDepth(depth);
        Document out = new Document();
        filter.forEach((rawKey, value) -> {
            String key = String.valueOf(rawKey);
            if (LOGICAL.contains(key)) {
                out.append(key, logical(key, value, depth));
            } else if (key.startsWith("$")) {
                throw DataException.badRequest("Operator " + key + " is not allowed here");
            } else {
                out.append(field(key), condition(key, value, depth));
            }
        });
        return out;
    }

    private static List<Document> logical(String operator, Object value, int depth) {
        if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > MAX_LIST) {
            throw DataException.badRequest(operator + " needs a list of 1 to " + MAX_LIST + " filters");
        }
        return list.stream().map(item -> {
            if (!(item instanceof Map<?, ?> map)) {
                throw DataException.badRequest(operator + " needs a list of filters");
            }
            return translate(map, depth + 1);
        }).toList();
    }

    private static Object condition(String path, Object value, int depth) {
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().noneMatch(FilterTranslator::isOperator)) {
            // A plain value or object: exact match.
            return literal(value, depth + 1);
        }
        Document operators = new Document();
        map.forEach((rawOperator, operand) -> {
            String operator = String.valueOf(rawOperator);
            if (!OPERATORS.contains(operator)) {
                throw DataException.badRequest("Operator " + operator + " is not allowed (on '" + path + "'); "
                        + "allowed: " + String.join(" ", OPERATORS.stream().sorted().toList()));
            }
            operators.append(operator, operand(operator, operand, depth + 1));
        });
        return operators;
    }

    private static Object operand(String operator, Object operand, int depth) {
        switch (operator) {
            case "$in", "$nin" -> {
                if (!(operand instanceof List<?> list) || list.size() > MAX_LIST) {
                    throw DataException.badRequest(operator + " needs a list of at most " + MAX_LIST + " values");
                }
                return list.stream().map(item -> literal(item, depth + 1)).toList();
            }
            case "$exists" -> {
                if (!(operand instanceof Boolean)) {
                    throw DataException.badRequest("$exists needs true or false");
                }
                return operand;
            }
            default -> {
                return literal(operand, depth);
            }
        }
    }

    /** A value compared as is. It may be an object, but one without operators anywhere inside. */
    private static Object literal(Object value, int depth) {
        requireDepth(depth);
        if (value instanceof Map<?, ?> map) {
            Document out = new Document();
            map.forEach((key, nested) -> {
                if (isOperator(key)) {
                    throw DataException.badRequest("Operator " + key + " is not allowed inside a value");
                }
                out.append(String.valueOf(key), literal(nested, depth + 1));
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

    private static boolean isOperator(Object key) {
        return String.valueOf(key).startsWith("$");
    }

    private static void requireDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw DataException.badRequest("The filter is nested deeper than " + MAX_DEPTH + " levels");
        }
    }
}
