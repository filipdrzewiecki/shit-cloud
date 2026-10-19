package com.drzewiecki.shitcloud.data;

import java.util.Map;

/**
 * @param filter Mongo-style filter on the app's fields (see {@link FilterTranslator}); none = everything
 * @param sort   {@code {field: 1 | -1}}; none = oldest first
 * @param limit  none = {@code data.max-results}, which is also the most allowed
 */
public record DataQuery(Map<String, Object> filter, Map<String, Object> sort, Integer limit) {
}
