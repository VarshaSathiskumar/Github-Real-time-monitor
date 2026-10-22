package com.githubpulse.analytics.store;

import com.githubpulse.analytics.model.ActivityCounts;
import com.githubpulse.analytics.model.MinuteCounts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Historical windows served from the minute and repository aggregate tables. Results are memoised
 * for a few seconds because long windows are expensive and change slowly.
 */
@Component
public class PostgresActivityStore implements ActivityStore {

    private static final String COUNT_COLUMNS =
            "total, push, pull_request, fork, watch, issues, issue_comments, other, commits, commit_reports";
    private static final String SUM_COLUMNS = "sum(total), sum(push), sum(pull_request), sum(fork), sum(watch), "
            + "sum(issues), sum(issue_comments), sum(other), sum(commits), sum(commit_reports)";

    private final JdbcTemplate jdbc;
    private final TtlCache cache = new TtlCache(Duration.ofSeconds(10));

    public PostgresActivityStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<MinuteCounts> minuteCounts(Instant from, Instant to) {
        return cache.get("min:" + from + to, () -> jdbc.query(
                "SELECT minute, " + COUNT_COLUMNS + " FROM minute_aggregates WHERE minute >= ? AND minute < ? ORDER BY minute",
                (rs, i) -> new MinuteCounts(rs.getTimestamp(1).toInstant(), counts(rs, 2)),
                Timestamp.from(from),
                Timestamp.from(to)));
    }

    @Override
    public long activeRepositories(Instant from, Instant to) {
        return cache.get("active:" + from + to, () -> {
            Long n = jdbc.queryForObject(
                    "SELECT count(DISTINCT repo_name) FROM repo_minute_aggregates WHERE minute >= ? AND minute < ?",
                    Long.class,
                    Timestamp.from(from),
                    Timestamp.from(to));
            return n == null ? 0L : n;
        });
    }

    @Override
    public List<Long> activeRepositorySeries(Instant from, Instant to, int bucketMinutes) {
        return cache.get("activeSeries:" + from + to + bucketMinutes, () -> {
            long firstBucket = Math.floorDiv(from.getEpochSecond() / 60, (long) bucketMinutes) * bucketMinutes;
            long lastMinute = Math.floorDiv(to.minusSeconds(1).getEpochSecond(), 60L);
            Map<Long, Long> byBucket = new HashMap<>();
            jdbc.query(
                    "SELECT date_bin(make_interval(mins => ?), minute, TIMESTAMPTZ 'epoch') AS b, "
                            + "count(DISTINCT repo_name) FROM repo_minute_aggregates "
                            + "WHERE minute >= ? AND minute < ? GROUP BY b",
                    rs -> {
                        byBucket.put(rs.getTimestamp(1).toInstant().getEpochSecond() / 60, rs.getLong(2));
                    },
                    bucketMinutes,
                    Timestamp.from(from),
                    Timestamp.from(to));
            List<Long> out = new ArrayList<>();
            for (long b = firstBucket; b <= lastMinute; b += bucketMinutes) {
                out.add(byBucket.getOrDefault(b, 0L));
            }
            return out;
        });
    }

    @Override
    public Map<String, ActivityCounts> repositoryTotals(Instant from, Instant to, int limit) {
        return cache.get("repoTotals:" + from + to + limit, () -> {
            Map<String, ActivityCounts> out = new LinkedHashMap<>();
            jdbc.query(
                    "SELECT repo_name, " + SUM_COLUMNS + " FROM repo_minute_aggregates "
                            + "WHERE minute >= ? AND minute < ? GROUP BY repo_name ORDER BY sum(total) DESC LIMIT ?",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.put(rs.getString(1), counts(rs, 2)),
                    Timestamp.from(from),
                    Timestamp.from(to),
                    limit);
            return out;
        });
    }

    @Override
    public Map<String, List<MinuteCounts>> repositoryMinutes(Collection<String> repos, Instant from, Instant to) {
        Map<String, List<MinuteCounts>> out = new HashMap<>();
        repos.forEach(r -> out.put(r, new ArrayList<>()));
        if (repos.isEmpty()) {
            return out;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(repos.size(), "?"));
        List<Object> args = new ArrayList<>(repos);
        args.add(Timestamp.from(from));
        args.add(Timestamp.from(to));
        jdbc.query(
                "SELECT repo_name, minute, " + COUNT_COLUMNS + " FROM repo_minute_aggregates WHERE repo_name IN ("
                        + placeholders + ") AND minute >= ? AND minute < ? ORDER BY minute",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.get(rs.getString(1))
                        .add(new MinuteCounts(rs.getTimestamp(2).toInstant(), counts(rs, 3))),
                args.toArray());
        return out;
    }

    public java.util.Optional<Instant> earliestMinute() {
        Timestamp t = jdbc.queryForObject("SELECT min(minute) FROM minute_aggregates", Timestamp.class);
        return java.util.Optional.ofNullable(t).map(Timestamp::toInstant);
    }

    private static ActivityCounts counts(ResultSet rs, int offset) throws SQLException {
        return new ActivityCounts(
                rs.getLong(offset),
                rs.getLong(offset + 1),
                rs.getLong(offset + 2),
                rs.getLong(offset + 3),
                rs.getLong(offset + 4),
                rs.getLong(offset + 5),
                rs.getLong(offset + 6),
                rs.getLong(offset + 7),
                rs.getLong(offset + 8),
                rs.getLong(offset + 9));
    }
}
