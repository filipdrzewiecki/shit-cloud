package com.drzewiecki.shitcloud.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * {@code timestamptz} ↔ {@link Instant}. The PostgreSQL driver reads and writes {@link OffsetDateTime}, not
 * {@code Instant}, so every timestamp crosses this boundary here.
 */
public final class Timestamps {

    private Timestamps() {
    }

    public static OffsetDateTime of(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    public static Instant read(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
