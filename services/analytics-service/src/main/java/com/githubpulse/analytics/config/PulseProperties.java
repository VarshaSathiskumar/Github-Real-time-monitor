package com.githubpulse.analytics.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** All tunables of the analytics service, bound from the {@code pulse.*} namespace. */
@ConfigurationProperties(prefix = "pulse")
public record PulseProperties(
        Topics topics,
        Score score,
        Momentum momentum,
        Retention retention,
        RedisSettings redis,
        Ws ws,
        IngestionGrpc ingestionGrpc,
        StreamSettings streams,
        Demo demo,
        String corsAllowedOrigins) {

    public record Topics(String raw, String dlq, int rawPartitions, short replicationFactor) {}

    /** Weights of the documented activity score. Watch, issue and comment events count as "other". */
    public record Score(double push, double pullRequest, double fork, double other) {}

    public record Momentum(
            Duration window,
            int baselineWindows,
            int minHistoryWindows,
            long minEvents,
            double spikeRatio,
            double minBaseline,
            Duration visibleFor) {}

    public record Retention(
            Duration events, Duration repoMinutes, Duration minutes, Duration rising, Duration failures) {}

    public record RedisSettings(Duration keyTtl, int recentEventsMax) {}

    public record Ws(Duration interval) {}

    public record IngestionGrpc(String target, Duration deadline) {}

    public record StreamSettings(Duration dedupTtl, int persistRetries, Duration retryBackoff, Duration gracePeriod) {}

    public record Demo(Duration backfill, Duration idleTimeout, double eventsPerSecond) {}
}
