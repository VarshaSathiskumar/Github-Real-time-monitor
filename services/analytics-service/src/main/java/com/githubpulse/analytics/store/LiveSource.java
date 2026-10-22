package com.githubpulse.analytics.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.githubpulse.analytics.config.PulseProperties;
import com.githubpulse.analytics.model.EventKind;
import com.githubpulse.analytics.model.EventView;
import com.githubpulse.analytics.model.Mode;
import com.githubpulse.analytics.model.Range;
import com.githubpulse.analytics.model.RisingRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Real GitHub data: Redis for the live window, PostgreSQL for longer ranges and as fallback. */
@Component
public class LiveSource implements DashboardSource {

    private static final Logger log = LoggerFactory.getLogger(LiveSource.class);

    private final RedisActivityStore redisStore;
    private final PostgresActivityStore postgresStore;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PulseProperties props;
    private final TtlCache cache = new TtlCache(Duration.ofSeconds(30));

    public LiveSource(
            RedisActivityStore redisStore,
            PostgresActivityStore postgresStore,
            StringRedisTemplate redis,
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PulseProperties props) {
        this.redisStore = redisStore;
        this.postgresStore = postgresStore;
        this.redis = redis;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.props = props;
    }

    @Override
    public Mode mode() {
        return Mode.LIVE;
    }

    @Override
    public Instant now() {
        return Instant.now();
    }

    @Override
    public ActivityStore store(Range range) {
        return range.live() ? redisStore : postgresStore;
    }

    @Override
    public Optional<Instant> observationStart() {
        return cache.get("obs", postgresStore::earliestMinute);
    }

    @Override
    public List<EventView> recentEvents(int limit) {
        try {
            List<String> raw = redis.opsForList().range(RedisKeys.RECENT, 0, limit - 1L);
            if (raw != null && !raw.isEmpty()) {
                List<EventView> out = new ArrayList<>(raw.size());
                for (String json : raw) {
                    out.add(mapper.readValue(json, EventView.class));
                }
                return out;
            }
        } catch (Exception e) {
            log.warn("Recent events from Redis unavailable, using PostgreSQL: {}", e.toString());
        }
        return jdbc.query(
                "SELECT event_id, kind, event_type, actor_login, repo_name, description, url, created_at, observed_at "
                        + "FROM github_events ORDER BY observed_at DESC LIMIT ?",
                (rs, i) -> new EventView(
                        Long.toString(rs.getLong(1)),
                        EventKind.valueOf(rs.getString(2)),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        "https://github.com/" + rs.getString(5),
                        rs.getString(6),
                        rs.getString(7),
                        rs.getTimestamp(8).toInstant(),
                        rs.getTimestamp(9).toInstant(),
                        false),
                limit);
    }

    @Override
    public List<RisingRepository> rising() {
        Instant cutoff = Instant.now().minus(props.momentum().visibleFor());
        List<RisingRepository> out = new ArrayList<>();
        try {
            Map<Object, Object> all = redis.opsForHash().entries(RedisKeys.RISING);
            for (Map.Entry<Object, Object> e : all.entrySet()) {
                RisingRepository r = mapper.readValue((String) e.getValue(), RisingRepository.class);
                if (r.windowStart().isBefore(cutoff)) {
                    redis.opsForHash().delete(RedisKeys.RISING, e.getKey());
                } else {
                    out.add(r);
                }
            }
        } catch (Exception e) {
            log.warn("Rising repositories unavailable: {}", e.toString());
        }
        out.sort(Comparator.comparingLong(RisingRepository::currentCount).reversed());
        return out.size() > 10 ? out.subList(0, 10) : out;
    }

    /** Latest time any event was observed, or empty if nothing has been stored yet. */
    public Optional<Instant> lastEventObservedAt() {
        Timestamp t = jdbc.queryForObject("SELECT max(observed_at) FROM github_events", Timestamp.class);
        return Optional.ofNullable(t).map(Timestamp::toInstant);
    }
}
