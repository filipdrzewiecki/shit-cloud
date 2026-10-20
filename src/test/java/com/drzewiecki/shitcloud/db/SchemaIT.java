package com.drzewiecki.shitcloud.db;

import com.drzewiecki.shitcloud.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Flyway builds the schema at startup; the constraints the code relies on exist. */
class SchemaIT extends IntegrationTest {

    @Test
    void migrations_areApplied() {
        List<String> applied = jdbc.sql("select version from flyway_schema_history where success order by installed_rank")
                .query(String.class).list();

        assertThat(applied).contains("1");
    }

    @Test
    void tablesExist() {
        List<String> tables = jdbc.sql("""
                        select table_name from information_schema.tables
                        where table_schema = current_schema() and table_type = 'BASE TABLE'""")
                .query(String.class).list();

        assertThat(tables).contains("apps", "app_versions", "docs", "doc_history");
    }

    @Test
    void indexesTheCodeReliesOn_exist() {
        List<String> indexes = jdbc.sql("select indexname from pg_indexes where schemaname = current_schema()")
                .query(String.class).list();

        assertThat(indexes).contains("apps_last_used_at", "app_versions_created_at", "doc_history_doc",
                "doc_history_changed_at", "docs_space_collection_doc_id_key", "app_versions_app_slug_version_key");
    }

    @Test
    void appDataCannotExistWithoutItsApp() {
        long withoutApp = jdbc.sql("""
                        select count(*) from information_schema.table_constraints
                        where table_name = 'docs' and constraint_type = 'FOREIGN KEY'""")
                .query(Long.class).single();

        assertThat(withoutApp).isEqualTo(1);
    }
}
