package com.githubpulse.analytics.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.githubpulse.analytics.config.PulseProperties;
import com.githubpulse.analytics.model.EventView;
import com.githubpulse.analytics.model.Mode;
import com.githubpulse.analytics.model.ParsedEvent;
import com.githubpulse.analytics.stream.Sinks;
import java.sql.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persists events idempotently. The primary key on {@code event_id} is the last line of defence
 * against duplicates: a second insert of the same event returns {@code DUPLICATE} and changes nothing.
 */
@Component
public class EventPersistence implements Sinks.EventSink {

    private static final Logger log = LoggerFactory.getLogger(EventPersistence.class);

    private static final String INSERT_EVENT = """
            INSERT INTO github_events (event_id, event_type, kind, actor_login, repo_id, repo_name, commits, action,
                                       description, url, created_at, ingested_at, observed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private static final String UPSERT_REPO = """
            INSERT INTO repositories (repo_id, full_name, owner, html_url, first_seen_at, last_seen_at, event_count)
            VALUES (?, ?, ?, ?, ?, ?, 1)
            ON CONFLICT (repo_id) DO UPDATE SET
                full_name = EXCLUDED.full_name,
                last_seen_at = GREATEST(repositories.last_seen_at, EXCLUDED.last_seen_at),
                event_count = repositories.event_count + 1
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final StringRedisTemplate redis;
    private final EventFeed feed;
    private final PulseProperties props;
    private final ObjectMapper mapper;

    public EventPersistence(
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            StringRedisTemplate redis,
            EventFeed feed,
            PulseProperties props,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.redis = redis;
        this.feed = feed;
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public Sinks.PersistResult persist(ParsedEvent e) {
        Boolean inserted = tx.execute(status -> {
            int rows = jdbc.update(
                    INSERT_EVENT,
                    e.id(),
                    e.type(),
                    e.kind().name(),
                    e.actor(),
                    e.repoId(),
                    e.repoName(),
                    e.commits(),
                    e.action(),
                    trim(e.description(), 400),
                    trim(e.url(), 500),
                    Timestamp.from(e.createdAt()),
                    Timestamp.from(e.ingestedAt()),
                    Timestamp.from(e.observedAt()));
            if (rows == 0) {
                return false;
            }
            jdbc.update(
                    UPSERT_REPO,
                    e.repoId(),
                    e.repoName(),
                    e.owner(),
                    e.repoUrl(),
                    Timestamp.from(e.observedAt()),
                    Timestamp.from(e.observedAt()));
            return true;
        });
        if (!Boolean.TRUE.equals(inserted)) {
            return Sinks.PersistResult.DUPLICATE;
        }
        publishLive(e);
        return Sinks.PersistResult.NEW;
    }

    /** Redis and the live feed are best effort: PostgreSQL already holds the durable copy. */
    private void publishLive(ParsedEvent e) {
        EventView view = e.toView(false);
        feed.offer(Mode.LIVE, view);
        try {
            long ttlSeconds = props.redis().keyTtl().toSeconds();
            String hll = RedisKeys.repoSet(RedisKeys.epochMinute(e.observedAt()));
            redis.opsForHyperLogLog().add(hll, e.repoName());
            redis.expire(hll, props.redis().keyTtl());
            redis.opsForList().leftPush(RedisKeys.RECENT, mapper.writeValueAsString(view));
            redis.opsForList().trim(RedisKeys.RECENT, 0, props.redis().recentEventsMax() - 1L);
            redis.expire(RedisKeys.RECENT, java.time.Duration.ofSeconds(ttlSeconds));
        } catch (JsonProcessingException | RuntimeException ex) {
            log.warn("Redis update skipped for event {}: {}", e.id(), ex.toString());
        }
    }

    private static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
