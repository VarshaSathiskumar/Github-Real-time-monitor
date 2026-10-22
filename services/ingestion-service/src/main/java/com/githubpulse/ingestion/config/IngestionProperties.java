package com.githubpulse.ingestion.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ingestion settings. The GitHub token is optional and only ever read from the environment
 * (GITHUB_TOKEN), never hardcoded.
 */
@ConfigurationProperties(prefix = "pulse.ingestion")
public record IngestionProperties(
        String githubApiUrl,
        String githubToken,
        int pages,
        int perPage,
        Duration minPollInterval,
        Duration maxBackoff,
        Duration requestTimeout,
        Duration publishTimeout,
        int dedupCacheSize,
        int rateLimitReserve,
        String rawTopic,
        String dlqTopic,
        int rawTopicPartitions,
        short topicReplicationFactor,
        int grpcPort,
        boolean pollingEnabled) {

    public boolean hasToken() {
        return githubToken != null && !githubToken.isBlank();
    }

    /** Without a token GitHub allows 60 requests per hour, so only the first page is polled. */
    public int effectivePages() {
        return hasToken() ? Math.max(1, pages) : 1;
    }
}
