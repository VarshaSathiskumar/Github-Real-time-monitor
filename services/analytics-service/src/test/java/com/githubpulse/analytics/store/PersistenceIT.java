package com.githubpulse.analytics.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.githubpulse.analytics.TestProps;
import com.githubpulse.analytics.model.ActivityCounts;
import com.githubpulse.analytics.model.MinuteCounts;
import com.githubpulse.analytics.model.Mode;
import com.githubpulse.analytics.model.ParsedEvent;
import com.githubpulse.analytics.model.RisingRepository;
import com.githubpulse.analytics.stream.EventParser;
import com.githubpulse.analytics.stream.Sinks;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Runs the real SQL and Flyway migrations against an embedded PostgreSQL (no Docker needed). */
class PersistenceIT {

    private static PostgresTestSupport db;

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
    private final EventFeed feed = new EventFeed();
    private EventPersistence persistence;
    private AggregateWriter aggregates;
    private PostgresActivityStore store;

    @BeforeAll
    static void startDb() throws Exception {
        db = new PostgresTestSupport();
    }

    @AfterAll
    static void stopDb() throws Exception {
        db.close();
    }

    @BeforeEach
    void setUp() {
        db.reset();
        var props = TestProps.create();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        persistence = new EventPersistence(db.jdbc, db.tx, redis, feed, props, mapper);
        aggregates = new AggregateWriter(db.jdbc, redis, props, mapper);
        store = new PostgresActivityStore(db.jdbc);
    }

    private ParsedEvent event(long id, String repo, Instant observed) {
        return EventParser.parse(TestProps.pushJson(id, repo)).withObservedAt(observed);
    }

    private static Instant minute(int offset) {
        return Instant.parse("2026-02-01T10:00:00Z").plus(offset, ChronoUnit.MINUTES);
    }

    @Test
    void insertingTheSameEventTwiceStoresItOnce() {
        ParsedEvent e = event(1, "octo/repo", minute(0));

        assertThat(persistence.persist(e)).isEqualTo(Sinks.PersistResult.NEW);
        assertThat(persistence.persist(e)).isEqualTo(Sinks.PersistResult.DUPLICATE);

        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM github_events", Integer.class)).isEqualTo(1);
        assertThat(db.jdbc.queryForObject("SELECT event_count FROM repositories WHERE full_name='octo/repo'", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void storesOnlyNecessaryFieldsAndAllowsUnknownCommitCount() {
        persistence.persist(event(2, "octo/repo", minute(0)));

        Map<String, Object> row = db.jdbc.queryForMap("SELECT * FROM github_events WHERE event_id = 2");
        assertThat(row.get("commits")).isNull();
        assertThat(row.get("kind")).isEqualTo("PUSH");
        assertThat(row.get("repo_name")).isEqualTo("octo/repo");
        assertThat(row).doesNotContainKey("payload");
    }

    @Test
    void newEventsAreHandedToTheLiveFeedButDuplicatesAreNot() {
        ParsedEvent e = event(3, "octo/repo", minute(0));

        persistence.persist(e);
        persistence.persist(e);

        assertThat(feed.drain(Mode.LIVE, 10)).hasSize(1);
        assertThat(feed.drain(Mode.DEMO, 10)).isEmpty();
    }

    @Test
    void repositoryMetadataIsUpsertedAndCounted() {
        persistence.persist(event(10, "octo/repo", minute(0)));
        persistence.persist(event(11, "octo/repo", minute(1)));
        persistence.persist(event(12, "other/thing", minute(1)));

        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM repositories", Integer.class)).isEqualTo(2);
        assertThat(db.jdbc.queryForObject("SELECT event_count FROM repositories WHERE full_name='octo/repo'", Long.class))
                .isEqualTo(2L);
        assertThat(db.jdbc.queryForObject("SELECT owner FROM repositories WHERE full_name='octo/repo'", String.class))
                .isEqualTo("octo");
    }

    @Test
    void aggregateUpsertsAreIdempotentAndNeverMoveBackwards() {
        var high = new ActivityCounts(10, 6, 2, 1, 1, 0, 0, 0, 0, 0);
        var low = new ActivityCounts(4, 2, 1, 1, 0, 0, 0, 0, 0, 0);

        aggregates.minute(minute(0), high);
        aggregates.minute(minute(0), high);
        aggregates.minute(minute(0), low); // replay after a restart with partial state

        List<MinuteCounts> rows = store.minuteCounts(minute(0), minute(1));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).counts()).isEqualTo(high);
    }

    @Test
    void readsMinuteCountsInRange() {
        aggregates.minute(minute(0), new ActivityCounts(1, 1, 0, 0, 0, 0, 0, 0, 0, 0));
        aggregates.minute(minute(5), new ActivityCounts(2, 2, 0, 0, 0, 0, 0, 0, 0, 0));
        aggregates.minute(minute(30), new ActivityCounts(9, 9, 0, 0, 0, 0, 0, 0, 0, 0));

        List<MinuteCounts> rows = store.minuteCounts(minute(0), minute(10));

        assertThat(rows).extracting(r -> r.counts().total()).containsExactly(1L, 2L);
    }

    @Test
    void repositoryTotalsAndSeries() {
        aggregates.repoMinute(minute(0), "a/one", new ActivityCounts(3, 3, 0, 0, 0, 0, 0, 0, 0, 0));
        aggregates.repoMinute(minute(1), "a/one", new ActivityCounts(2, 1, 1, 0, 0, 0, 0, 0, 0, 0));
        aggregates.repoMinute(minute(1), "b/two", new ActivityCounts(1, 0, 0, 1, 0, 0, 0, 0, 0, 0));

        Map<String, ActivityCounts> totals = store.repositoryTotals(minute(0), minute(10), 10);
        Map<String, List<MinuteCounts>> series = store.repositoryMinutes(List.of("a/one"), minute(0), minute(10));

        assertThat(totals.keySet()).containsExactly("a/one", "b/two");
        assertThat(totals.get("a/one").total()).isEqualTo(5);
        assertThat(totals.get("a/one").pullRequest()).isEqualTo(1);
        assertThat(series.get("a/one")).hasSize(2);
        assertThat(store.activeRepositories(minute(0), minute(10))).isEqualTo(2);
    }

    @Test
    void activeRepositorySeriesBucketsDistinctRepositories() {
        aggregates.repoMinute(minute(0), "a/one", new ActivityCounts(1, 1, 0, 0, 0, 0, 0, 0, 0, 0));
        aggregates.repoMinute(minute(1), "a/one", new ActivityCounts(1, 1, 0, 0, 0, 0, 0, 0, 0, 0));
        aggregates.repoMinute(minute(1), "b/two", new ActivityCounts(1, 1, 0, 0, 0, 0, 0, 0, 0, 0));
        aggregates.repoMinute(minute(7), "c/three", new ActivityCounts(1, 1, 0, 0, 0, 0, 0, 0, 0, 0));

        // 10:00 is epoch aligned to 5 minute buckets, so buckets are 10:00-10:04 and 10:05-10:09
        List<Long> series = store.activeRepositorySeries(minute(0), minute(10), 5);

        assertThat(series).containsExactly(2L, 1L);
    }

    @Test
    void risingRepositoriesAreStoredOncePerWindow() {
        var spike = new RisingRepository(
                "hot/repo", "https://github.com/hot/repo", minute(0), 5, 10, 2.0, 400.0, "RISING", "n", minute(1));
        aggregates.spike(spike);
        aggregates.spike(spike);

        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM rising_repositories", Integer.class)).isEqualTo(1);
    }

    @Test
    void retentionDeletesOnlyOldRows() {
        Instant now = Instant.now();
        persistence.persist(event(20, "octo/repo", now.minus(100, ChronoUnit.HOURS)));
        persistence.persist(event(21, "octo/repo", now));

        int removed = new RetentionJob(db.jdbc, TestProps.create()).purge("github_events", "observed_at", now.minus(48, ChronoUnit.HOURS));

        assertThat(removed).isEqualTo(1);
        assertThat(db.jdbc.queryForObject("SELECT event_id FROM github_events", Long.class)).isEqualTo(21L);
    }

    @Test
    void earliestMinuteReflectsFirstAggregate() {
        assertThat(store.earliestMinute()).isEmpty();
        aggregates.minute(minute(3), new ActivityCounts(1, 1, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThat(store.earliestMinute()).contains(minute(3));
        assertThat(Timestamp.from(minute(3))).isNotNull();
    }
}
