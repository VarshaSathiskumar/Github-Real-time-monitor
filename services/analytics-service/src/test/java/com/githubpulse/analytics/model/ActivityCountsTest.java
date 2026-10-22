package com.githubpulse.analytics.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.githubpulse.analytics.TestProps;
import org.junit.jupiter.api.Test;

class ActivityCountsTest {

    @Test
    void scoreUsesDocumentedWeights() {
        // 4 push, 2 PR, 1 fork, 3 stars, 1 issue => 4*2 + 2*3 + 1*2 + (3+1)*1 = 20
        ActivityCounts c = new ActivityCounts(11, 4, 2, 1, 3, 1, 0, 0, 0, 0);

        assertThat(c.score(TestProps.create().score())).isEqualTo(20.0);
    }

    @Test
    void weightsAreConfigurable() {
        ActivityCounts c = new ActivityCounts(10, 10, 0, 0, 0, 0, 0, 0, 0, 0);
        var heavyPush = new com.githubpulse.analytics.config.PulseProperties.Score(5, 3, 2, 1);

        assertThat(c.score(heavyPush)).isEqualTo(50.0);
    }

    @Test
    void compactFormRoundTrips() {
        ActivityCounts c = new ActivityCounts(9, 4, 2, 1, 1, 1, 0, 0, 12, 3);

        assertThat(ActivityCounts.fromCompact(c.toCompact())).isEqualTo(c);
    }

    @Test
    void readsLegacyNineFieldCompactForm() {
        assertThat(ActivityCounts.fromCompact("9,4,2,1,1,1,0,0,12").commitReports()).isZero();
    }

    @Test
    void commitsOnlyCountWhenReported() {
        var withCommits = TestProps.eventJson(1, "PushEvent", "a/b", "c", "2026-01-01T00:00:00Z", "{\"size\":4}");
        var without = TestProps.pushJson(2, "a/b");

        ActivityCounts a = ActivityCounts.of(com.githubpulse.analytics.stream.EventParser.parse(withCommits));
        ActivityCounts b = ActivityCounts.of(com.githubpulse.analytics.stream.EventParser.parse(without));

        assertThat(a.commits()).isEqualTo(4);
        assertThat(a.commitReports()).isEqualTo(1);
        assertThat(b.commits()).isZero();
        assertThat(b.commitReports()).isZero();
        assertThat(a.plus(b).push()).isEqualTo(2);
    }
}
