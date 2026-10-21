package com.drzewiecki.shitcloud.buildchat;

import com.drzewiecki.shitcloud.data.DependencyView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the model reads; reading the log is covered in {@code DependencyLogIT}.
 */
class GetDepsToolTest {

    @Test
    void oneLinePerApp_withWhatItReadsAndWrites_theAppItselfMarked() {
        List<DependencyView> rows = List.of(
                row("office-report", "Office Report", false, "marks", "read", "2026-10-21T09:00:00Z"),
                row("team-calendar", "Team Calendar", false, "marks", "write", "2026-10-21T10:00:00Z"),
                row("team-calendar", "Team Calendar", false, "people", "read", "2026-10-20T10:00:00Z"),
                row("team-calendar", "Team Calendar", false, "marks", "read", "2026-10-19T10:00:00Z"),
                row("lunch-stats", "Lunch Stats", true, "people", "read", "2026-09-01T10:00:00Z"));

        assertThat(GetDepsTool.describe("team-calendar", rows)).isEqualTo("""
                Apps that used the data of team-calendar:
                - lunch-stats (Lunch Stats, archived): reads people; last used 2026-09-01
                - office-report (Office Report): reads marks; last used 2026-10-21
                - team-calendar (Team Calendar), the app itself: reads marks, people; writes marks; last used 2026-10-21""");
    }

    @Test
    void onlyTheAppItself_saysNoOtherAppUsesIt() {
        assertThat(GetDepsTool.describe("team-calendar",
                List.of(row("team-calendar", "Team Calendar", false, "marks", "write", "2026-10-21T10:00:00Z"))))
                .startsWith("No other app used the data of team-calendar in the last 90 days.\n- team-calendar");
        assertThat(GetDepsTool.describe("team-calendar", List.of()))
                .isEqualTo("No other app used the data of team-calendar in the last 90 days.");
    }

    private static DependencyView row(String app, String name, boolean archived, String collection, String mode,
                                      String lastSeen) {
        return new DependencyView(app, name, archived, collection, mode, Instant.parse(lastSeen), Instant.parse(lastSeen));
    }
}
