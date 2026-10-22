package com.githubpulse.analytics;

import com.githubpulse.analytics.config.PulseProperties;
import java.time.Duration;

public final class TestProps {

    private TestProps() {}

    public static PulseProperties create() {
        return create("github.events.raw", "github.events.dlq");
    }

    public static PulseProperties create(String rawTopic, String dlqTopic) {
        return new PulseProperties(
                new PulseProperties.Topics(rawTopic, dlqTopic, 1, (short) 1),
                new PulseProperties.Score(2, 3, 2, 1),
                new PulseProperties.Momentum(Duration.ofMinutes(5), 3, 2, 5, 3.0, 1.0, Duration.ofMinutes(15)),
                new PulseProperties.Retention(
                        Duration.ofHours(48), Duration.ofHours(72), Duration.ofDays(30), Duration.ofDays(7), Duration.ofDays(14)),
                new PulseProperties.RedisSettings(Duration.ofHours(26), 100),
                new PulseProperties.Ws(Duration.ofSeconds(3)),
                new PulseProperties.IngestionGrpc("localhost:9090", Duration.ofSeconds(2)),
                new PulseProperties.StreamSettings(Duration.ofHours(2), 2, Duration.ofMillis(1), Duration.ofSeconds(30)),
                new PulseProperties.Demo(Duration.ofHours(6), Duration.ofSeconds(90), 4),
                "http://localhost:5173");
    }

    /** A GitHub-shaped event document as the ingestion service publishes it. */
    public static String eventJson(long id, String type, String repo, String actor, String createdAt, String payload) {
        return """
                {"id":"%d","type":"%s","actor":{"login":"%s"},"repo":{"id":%d,"name":"%s"},
                 "payload":%s,"created_at":"%s","ingested_at":"%s"}
                """.formatted(id, type, actor, Math.abs(repo.hashCode()), repo, payload, createdAt, createdAt);
    }

    public static String pushJson(long id, String repo) {
        return eventJson(id, "PushEvent", repo, "dev" + id, "2026-01-01T00:00:00Z", "{\"ref\":\"refs/heads/main\"}");
    }
}
