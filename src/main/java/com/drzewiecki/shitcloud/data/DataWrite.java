package com.drzewiecki.shitcloud.data;

import java.util.Map;

/**
 * @param data            the document's fields
 * @param expectedVersion optional: write only if the document is still at this version, else 409. Without it
 *                        the last write wins (the replaced state is kept in history either way)
 */
public record DataWrite(Map<String, Object> data, Long expectedVersion) {
}
