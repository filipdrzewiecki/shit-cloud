package com.drzewiecki.shitcloud.data;

import com.drzewiecki.shitcloud.IntegrationTest;
import com.drzewiecki.shitcloud.db.Timestamps;
import com.drzewiecki.shitcloud.model.DeployRequest;
import com.drzewiecki.shitcloud.service.AppRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static com.drzewiecki.shitcloud.data.DependencyLog.Mode.READ;
import static com.drzewiecki.shitcloud.data.DependencyLog.Mode.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class DependencyLogIT extends IntegrationTest {

    @Autowired
    private DependencyLog log;

    @Autowired
    private AppRegistry appRegistry;

    @BeforeEach
    void twoApps() {
        appRegistry.deploy(new DeployRequest("Team Calendar", null, "<p>x</p>"), BUILDER_EMAIL);
        appRegistry.deploy(new DeployRequest("Office Report", null, "<p>x</p>"), BUILDER_EMAIL);
    }

    @Test
    void record_addsARowPerReaderSpaceCollectionAndMode() {
        log.record("office-report", "team-calendar", "marks", READ);
        log.record("office-report", "team-calendar", "marks", READ);
        log.record("team-calendar", "team-calendar", "marks", WRITE);
        log.record("team-calendar", "team-calendar", "marks", READ);

        assertThat(rows()).containsExactlyInAnyOrder(
                "office-report team-calendar marks read",
                "team-calendar team-calendar marks write",
                "team-calendar team-calendar marks read");
    }

    @Test
    void lastSeen_isRefreshedAtMostHourly() {
        log.record("office-report", "team-calendar", "marks", READ);
        jdbc.sql("update app_deps set first_seen_at = now() - interval '30 minutes', "
                + "last_seen_at = now() - interval '30 minutes'").update();
        Instant halfAnHourAgo = lastSeen();

        log.record("office-report", "team-calendar", "marks", READ);
        assertThat(lastSeen()).isEqualTo(halfAnHourAgo);

        jdbc.sql("update app_deps set last_seen_at = now() - interval '2 hours'").update();
        log.record("office-report", "team-calendar", "marks", READ);
        assertThat(lastSeen()).isAfter(halfAnHourAgo);
        // The first use stays.
        assertThat(jdbc.sql("select first_seen_at < now() - interval '29 minutes' from app_deps")
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void readerThatIsNoApp_orNoSlug_isNotRecorded_andNeverThrows() {
        log.record("no-such-app", "team-calendar", "marks", READ);
        log.record(null, "team-calendar", "marks", READ);
        log.record("", "team-calendar", "marks", READ);
        log.record("Office Report; drop table", "team-calendar", "marks", READ);
        // A space that is no app breaks the foreign key: swallowed, the data call it describes already succeeded.
        log.record("office-report", "no-such-app", "marks", READ);

        assertThat(rows()).isEmpty();
    }

    @Test
    void deletingEitherApp_removesItsRows() {
        appRegistry.deploy(new DeployRequest("Lunch Stats", null, "<p>x</p>"), BUILDER_EMAIL);
        log.record("office-report", "team-calendar", "marks", READ);
        log.record("lunch-stats", "team-calendar", "people", READ);
        log.record("office-report", "lunch-stats", "votes", READ);

        appRegistry.delete("lunch-stats", "lunch-stats", BUILDER_EMAIL);

        assertThat(rows()).containsExactly("office-report team-calendar marks read");
    }

    @Test
    void users_listsTheSpaceUsers_withNames_newestFirst() {
        log.record("team-calendar", "team-calendar", "marks", WRITE);
        log.record("office-report", "team-calendar", "marks", READ);
        jdbc.sql("update app_deps set last_seen_at = now() - interval '1 day' where reader = 'team-calendar'").update();
        appRegistry.archive("office-report", BUILDER_EMAIL);

        List<DependencyView> users = log.users("team-calendar");

        assertThat(users).extracting(DependencyView::app, DependencyView::appName, DependencyView::archived,
                        DependencyView::collection, DependencyView::mode)
                .containsExactly(
                        tuple("office-report", "Office Report", true, "marks", "read"),
                        tuple("team-calendar", "Team Calendar", false, "marks", "write"));
        assertThat(log.users("office-report")).isEmpty();
        assertThatThrownBy(() -> log.users("nope")).isInstanceOfSatisfying(DataException.class,
                e -> assertThat(e.code()).isEqualTo(DataException.NO_SUCH_APP));
    }

    private List<String> rows() {
        return jdbc.sql("select reader || ' ' || space || ' ' || collection || ' ' || mode from app_deps")
                .query(String.class).list();
    }

    private Instant lastSeen() {
        return jdbc.sql("select last_seen_at from app_deps").query((row, i) -> Timestamps.read(row, "last_seen_at"))
                .single();
    }
}
