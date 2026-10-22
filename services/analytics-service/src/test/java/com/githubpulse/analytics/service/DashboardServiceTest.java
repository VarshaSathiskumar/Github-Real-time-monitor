package com.githubpulse.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.githubpulse.analytics.TestProps;
import com.githubpulse.analytics.api.Dtos.BreakdownResponse;
import com.githubpulse.analytics.api.Dtos.Kpi;
import com.githubpulse.analytics.api.Dtos.MetricsResponse;
import com.githubpulse.analytics.api.Dtos.TrendingResponse;
import com.githubpulse.analytics.demo.DemoDataStore;
import com.githubpulse.analytics.model.ActivityCounts;
import com.githubpulse.analytics.model.EventKind;
import com.githubpulse.analytics.model.Mode;
import com.githubpulse.analytics.model.Range;
import com.githubpulse.analytics.store.LiveSource;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DashboardServiceTest {

    private static final DashboardService.Comparison NO_COMPARISON =
            new DashboardService.Comparison(false, Instant.EPOCH, java.time.Duration.ZERO, null);

    private DemoDataStore data;
    private DashboardService service;

    @BeforeEach
    void setUp() {
        var props = TestProps.create();
        data = new DemoDataStore(props);
        service = new DashboardService(props, mock(LiveSource.class), data);
    }

    private void add(EventKind kind, String repo, int times) {
        for (int i = 0; i < times; i++) {
            data.add(Instant.now(), kind, repo, 2);
        }
    }

    @Test
    void trendingRanksByDocumentedActivityScore() {
        add(EventKind.PUSH, "a/pushy", 10); // 10 x 2 = 20
        add(EventKind.WATCH, "c/starred", 9); // 9 x 1 = 9
        add(EventKind.PULL_REQUEST, "b/reviewed", 2); // 2 x 3 = 6
        add(EventKind.FORK, "b/reviewed", 1); // + 2 = 8

        TrendingResponse t = service.trending(Mode.DEMO, Range.H1);

        assertThat(t.repositories()).extracting(r -> r.name()).containsExactly("a/pushy", "c/starred", "b/reviewed");
        assertThat(t.repositories().get(0).score()).isEqualTo(20.0);
        assertThat(t.repositories().get(0).rank()).isEqualTo(1);
        assertThat(t.repositories().get(0).share()).isEqualTo(1.0);
        assertThat(t.repositories().get(0).url()).isEqualTo("https://github.com/a/pushy");
        assertThat(t.repositories().get(2).pullRequestEvents()).isEqualTo(2);
        assertThat(t.repositories().get(2).forkEvents()).isEqualTo(1);
        assertThat(t.scoreFormula()).isEqualTo("Activity Score = Push x 2 + Pull Request x 3 + Fork x 2 + Other x 1");
        assertThat(t.disclaimer()).contains("not a ranking of GitHub's most popular repositories");
    }

    @Test
    void trendingReturnsAtMostFiveRepositories() {
        for (int i = 0; i < 8; i++) {
            add(EventKind.PUSH, "org/repo" + i, i + 1);
        }

        TrendingResponse t = service.trending(Mode.DEMO, Range.H1);

        assertThat(t.repositories()).hasSize(5);
        assertThat(t.repositories().get(0).name()).isEqualTo("org/repo7");
    }

    @Test
    void breakdownSumsToTotalWithPercentages() {
        add(EventKind.PUSH, "a/b", 6);
        add(EventKind.WATCH, "a/b", 3);
        add(EventKind.FORK, "a/b", 1);

        BreakdownResponse b = service.breakdown(Mode.DEMO, Range.H1);

        assertThat(b.total()).isEqualTo(10);
        assertThat(b.items()).hasSize(EventKind.values().length);
        assertThat(b.items().get(0).kind()).isEqualTo(EventKind.PUSH);
        assertThat(b.items().get(0).percent()).isEqualTo(60.0);
        assertThat(b.items().stream().mapToLong(i -> i.count()).sum()).isEqualTo(10);
    }

    @Test
    void metricsCountEventsAndRepositories() {
        add(EventKind.PUSH, "a/one", 4);
        add(EventKind.PULL_REQUEST, "b/two", 2);
        add(EventKind.FORK, "b/two", 1);

        MetricsResponse m = service.metrics(Mode.DEMO, Range.H1);

        assertThat(m.totalEvents().value()).isEqualTo(7);
        assertThat(m.activeRepositories().value()).isEqualTo(2);
        assertThat(m.pushEvents().value()).isEqualTo(4);
        assertThat(m.pullRequests().value()).isEqualTo(2);
        assertThat(m.forks().value()).isEqualTo(1);
        assertThat(m.commits().value()).isEqualTo(8);
        assertThat(m.mode()).isEqualTo("demo");
    }

    @Test
    void changeVersusPreviousWindowIsUnavailableUntilHistoryCoversIt() {
        add(EventKind.PUSH, "a/one", 4);

        MetricsResponse m = service.metrics(Mode.DEMO, Range.H1);

        assertThat(m.totalEvents().changePct()).isNull();
    }

    @Test
    void changeVersusPreviousWindowIsComputedWhenCovered() {
        Instant now = Instant.now();
        data.add(now.minusSeconds(119L * 60), EventKind.OTHER, "a/one", 1); // marks where observation began
        for (int i = 0; i < 10; i++) {
            data.add(now.minusSeconds(100L * 60), EventKind.PUSH, "a/one", 1); // previous 1h window
        }
        for (int i = 0; i < 15; i++) {
            data.add(now, EventKind.PUSH, "a/one", 1); // current window
        }

        MetricsResponse m = service.metrics(Mode.DEMO, Range.H1);

        assertThat(m.totalEvents().value()).isEqualTo(15);
        // previous window holds 10 push events plus the 1 marker event
        assertThat(m.totalEvents().changePct()).isEqualTo(36.4);
    }

    @Test
    void changeIsShownFromPartialHistoryWithAnHonestBasis() {
        Instant now = Instant.now();
        // 30 minutes observed: 10 events in the older 12 minutes, 25 in the newer 12 minutes
        for (int i = 0; i < 10; i++) {
            data.add(now.minusSeconds(28 * 60), EventKind.PUSH, "a/" + i, 1);
        }
        for (int i = 0; i < 25; i++) {
            data.add(now.minusSeconds(8 * 60), EventKind.PUSH, "a/" + i, 1);
        }

        MetricsResponse m = service.metrics(Mode.DEMO, Range.H1);

        assertThat(m.totalEvents().value()).isEqualTo(35);
        assertThat(m.totalEvents().changePct()).isEqualTo(150.0);
        assertThat(m.totalEvents().changeBasis()).contains("minutes of data observed so far");
    }

    @Test
    void changeVersusFullPreviousWindowNamesTheWindow() {
        Instant now = Instant.now();
        data.add(now.minusSeconds(119L * 60), EventKind.OTHER, "a/one", 1);
        for (int i = 0; i < 10; i++) {
            data.add(now.minusSeconds(100L * 60), EventKind.PUSH, "a/one", 1);
        }
        for (int i = 0; i < 15; i++) {
            data.add(now, EventKind.PUSH, "a/one", 1);
        }

        assertThat(service.metrics(Mode.DEMO, Range.H1).totalEvents().changeBasis())
                .isEqualTo("Compared with the previous 1h window");
    }

    @Test
    void commitsAreUnavailableWhenNoPushReportedACount() {
        ActivityCounts noCommitInfo = new ActivityCounts(5, 5, 0, 0, 0, 0, 0, 0, 0, 0);

        Kpi k = DashboardService.commitsKpi(noCommitInfo, ActivityCounts.ZERO, ActivityCounts.ZERO, NO_COMPARISON, List.of(1L));

        assertThat(k.value()).isNull();
        assertThat(k.note()).contains("no longer reports commit counts");
    }

    @Test
    void commitsKpiNotesPartialCoverage() {
        ActivityCounts partial = new ActivityCounts(8, 8, 0, 0, 0, 0, 0, 0, 12, 4);

        Kpi k = DashboardService.commitsKpi(partial, ActivityCounts.ZERO, ActivityCounts.ZERO, NO_COMPARISON, List.of());

        assertThat(k.value()).isEqualTo(12L);
        assertThat(k.note()).isEqualTo("Based on 4 of 8 push events that reported a commit count.");
    }

    @Test
    void activityPointsExposeEventsPerMinute() {
        Instant earlier = Instant.now().minusSeconds(150);
        for (int i = 0; i < 5; i++) {
            data.add(earlier, EventKind.PUSH, "a/one", 1);
        }
        data.add(earlier, EventKind.PULL_REQUEST, "a/one", 1);

        var activity = service.activity(Mode.DEMO, Range.M15);

        assertThat(activity.bucketMinutes()).isEqualTo(1);
        assertThat(activity.points().stream().mapToDouble(p -> p.total()).sum()).isEqualTo(6.0);
        assertThat(activity.points().stream().mapToDouble(p -> p.push()).sum()).isEqualTo(5.0);
    }

    @Test
    void currentIncompleteMinuteIsNotCharted() {
        add(EventKind.PUSH, "a/one", 5); // observed in the minute still in progress

        var activity = service.activity(Mode.DEMO, Range.M15);

        assertThat(activity.points().stream().mapToDouble(p -> p.total()).sum()).isZero();
    }

    @Test
    void emptyDemoStoreYieldsEmptyActivityNotZeroLine() {
        assertThat(service.activity(Mode.DEMO, Range.H1).points()).isEmpty();
    }

    @Test
    void rangeParsingAcceptsKnownCodesOnly() {
        assertThat(Range.parse("15m")).isEqualTo(Range.M15);
        assertThat(Range.parse(null)).isEqualTo(Range.H1);
        assertThat(Range.parse("24H")).isEqualTo(Range.H24);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Range.parse("2d"));
    }
}
