package com.githubpulse.analytics.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.githubpulse.analytics.config.PulseProperties;
import com.githubpulse.analytics.model.ActivityCounts;
import com.githubpulse.analytics.model.RisingRepository;
import com.githubpulse.analytics.stream.Sinks;
import java.sql.Timestamp;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes windowed aggregates to PostgreSQL (durable) and Redis (fast). Values arrive as absolute
 * window totals, and PostgreSQL keeps the greatest value seen, so re-delivery and replays are harmless.
 */
@Component
public class AggregateWriter implements Sinks.AggregateSink {

    private static final Logger log = LoggerFactory.getLogger(AggregateWriter.class);

    private static final String COLUMNS = "total, push, pull_request, fork, watch, issues, issue_comments, other, commits, commit_reports";

    private static final String UPSERT_MINUTE = """
            INSERT INTO minute_aggregates (minute, %s) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (minute) DO UPDATE SET
                total = GREATEST(minute_aggregates.total, EXCLUDED.total),
                push = GREATEST(minute_aggregates.push, EXCLUDED.push),
                pull_request = GREATEST(minute_aggregates.pull_request, EXCLUDED.pull_request),
                fork = GREATEST(minute_aggregates.fork, EXCLUDED.fork),
                watch = GREATEST(minute_aggregates.watch, EXCLUDED.watch),
                issues = GREATEST(minute_aggregates.issues, EXCLUDED.issues),
                issue_comments = GREATEST(minute_aggregates.issue_comments, EXCLUDED.issue_comments),
                other = GREATEST(minute_aggregates.other, EXCLUDED.other),
                commits = GREATEST(minute_aggregates.commits, EXCLUDED.commits),
                commit_reports = GREATEST(minute_aggregates.commit_reports, EXCLUDED.commit_reports),
                updated_at = now()
            """.formatted(COLUMNS);

    private static final String UPSERT_REPO_MINUTE = """
            INSERT INTO repo_minute_aggregates (minute, repo_name, %s) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (minute, repo_name) DO UPDATE SET
                total = GREATEST(repo_minute_aggregates.total, EXCLUDED.total),
                push = GREATEST(repo_minute_aggregates.push, EXCLUDED.push),
                pull_request = GREATEST(repo_minute_aggregates.pull_request, EXCLUDED.pull_request),
                fork = GREATEST(repo_minute_aggregates.fork, EXCLUDED.fork),
                watch = GREATEST(repo_minute_aggregates.watch, EXCLUDED.watch),
                issues = GREATEST(repo_minute_aggregates.issues, EXCLUDED.issues),
                issue_comments = GREATEST(repo_minute_aggregates.issue_comments, EXCLUDED.issue_comments),
                other = GREATEST(repo_minute_aggregates.other, EXCLUDED.other),
                commits = GREATEST(repo_minute_aggregates.commits, EXCLUDED.commits),
                commit_reports = GREATEST(repo_minute_aggregates.commit_reports, EXCLUDED.commit_reports),
                updated_at = now()
            """.formatted(COLUMNS);

    private static final String UPSERT_RISING = """
            INSERT INTO rising_repositories (repo_name, window_start, window_minutes, current_count, baseline,
                                             pct_increase, level, detected_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (repo_name, window_start) DO UPDATE SET
                current_count = GREATEST(rising_repositories.current_count, EXCLUDED.current_count),
                baseline = EXCLUDED.baseline,
                pct_increase = EXCLUDED.pct_increase,
                level = EXCLUDED.level,
                detected_at = EXCLUDED.detected_at
            """;

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final PulseProperties props;
    private final ObjectMapper mapper;

    public AggregateWriter(JdbcTemplate jdbc, StringRedisTemplate redis, PulseProperties props, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public void minute(Instant minute, ActivityCounts c) {
        jdbc.update(
                UPSERT_MINUTE,
                Timestamp.from(minute),
                c.total(),
                c.push(),
                c.pullRequest(),
                c.fork(),
                c.watch(),
                c.issues(),
                c.issueComments(),
                c.other(),
                c.commits(),
                c.commitReports());
        String key = RedisKeys.minute(RedisKeys.epochMinute(minute));
        redisQuietly(() -> redis.opsForValue().set(key, c.toCompact(), props.redis().keyTtl()));
    }

    @Override
    public void repoMinute(Instant minute, String repo, ActivityCounts c) {
        jdbc.update(
                UPSERT_REPO_MINUTE,
                Timestamp.from(minute),
                repo,
                c.total(),
                c.push(),
                c.pullRequest(),
                c.fork(),
                c.watch(),
                c.issues(),
                c.issueComments(),
                c.other(),
                c.commits(),
                c.commitReports());
        String key = RedisKeys.repoMinute(RedisKeys.epochMinute(minute));
        redisQuietly(() -> {
            redis.opsForHash().put(key, repo, c.toCompact());
            redis.expire(key, props.redis().keyTtl());
        });
    }

    @Override
    public void spike(RisingRepository s) {
        jdbc.update(
                UPSERT_RISING,
                s.repo(),
                Timestamp.from(s.windowStart()),
                s.windowMinutes(),
                s.currentCount(),
                s.baseline(),
                s.pctIncrease(),
                s.level(),
                Timestamp.from(s.detectedAt()));
        redisQuietly(() -> {
            try {
                redis.opsForHash().put(RedisKeys.RISING, s.repo(), mapper.writeValueAsString(s));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
            redis.expire(RedisKeys.RISING, java.time.Duration.ofHours(2));
        });
    }

    private void redisQuietly(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.warn("Redis write skipped: {}", e.toString());
        }
    }
}
