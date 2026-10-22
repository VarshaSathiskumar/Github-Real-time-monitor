package com.githubpulse.analytics.service;

import com.githubpulse.analytics.api.Dtos.AnalyticsInfo;
import com.githubpulse.analytics.api.Dtos.DemoInfo;
import com.githubpulse.analytics.api.Dtos.SystemStatus;
import com.githubpulse.analytics.demo.DemoSimulator;
import com.githubpulse.analytics.grpc.IngestionHealthClient;
import com.githubpulse.analytics.model.Mode;
import com.githubpulse.analytics.store.LiveSource;
import java.time.Duration;
import java.time.Instant;
import org.apache.kafka.streams.KafkaStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Service;

/** Aggregates health from ingestion (gRPC), Kafka Streams, Redis and PostgreSQL. */
@Service
public class SystemStatusService {

    public static final String DISCLAIMER =
            "Events are near real time. They come from GitHub's public events API, which is a sampled, "
                    + "delayed and rate-limited view of platform activity, so counts are not global totals.";

    private static final Logger log = LoggerFactory.getLogger(SystemStatusService.class);

    private final IngestionHealthClient ingestion;
    private final StreamsBuilderFactoryBean streams;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final ConsumerLagService lag;
    private final LiveSource live;
    private final DemoSimulator demo;
    private final com.githubpulse.analytics.store.TtlCache cache =
            new com.githubpulse.analytics.store.TtlCache(Duration.ofSeconds(3));

    public SystemStatusService(
            IngestionHealthClient ingestion,
            StreamsBuilderFactoryBean streams,
            StringRedisTemplate redis,
            JdbcTemplate jdbc,
            ConsumerLagService lag,
            LiveSource live,
            DemoSimulator demo) {
        this.ingestion = ingestion;
        this.streams = streams;
        this.redis = redis;
        this.jdbc = jdbc;
        this.lag = lag;
        this.live = live;
        this.demo = demo;
    }

    public SystemStatus status(Mode mode) {
        SystemStatus base = cache.get("status", this::build);
        return new SystemStatus(
                mode.code(),
                base.ingestion(),
                base.analytics(),
                new DemoInfo(demo.isRunning(), demo.startedAt()),
                DISCLAIMER,
                base.generatedAt());
    }

    private SystemStatus build() {
        KafkaStreams ks = streams.getKafkaStreams();
        String state = ks == null ? "NOT_STARTED" : ks.state().name();
        boolean redisUp = check(() -> redis.execute((org.springframework.data.redis.core.RedisCallback<String>) c -> c.ping()) != null);
        boolean pgUp = check(() -> jdbc.queryForObject("SELECT 1", Integer.class) != null);
        Instant lastEvent = null;
        if (pgUp) {
            try {
                lastEvent = live.lastEventObservedAt().orElse(null);
            } catch (RuntimeException e) {
                log.debug("Last event lookup failed: {}", e.toString());
            }
        }
        return new SystemStatus(
                "live",
                ingestion.fetch(),
                new AnalyticsInfo(state, redisUp, pgUp, lag.totalLag(), lastEvent),
                new DemoInfo(demo.isRunning(), demo.startedAt()),
                DISCLAIMER,
                Instant.now());
    }

    private static boolean check(java.util.function.BooleanSupplier probe) {
        try {
            return probe.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
