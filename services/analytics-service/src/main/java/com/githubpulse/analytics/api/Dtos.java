package com.githubpulse.analytics.api;

import com.githubpulse.analytics.model.EventKind;
import com.githubpulse.analytics.model.EventView;
import com.githubpulse.analytics.model.RisingRepository;
import java.time.Instant;
import java.util.List;

/** Response bodies of the REST API and payloads of WebSocket messages. */
public final class Dtos {

    private Dtos() {}

    /**
     * @param changePct percent change versus the preceding comparable span, null when there is too little observed data
     * @param changeBasis human readable description of what was compared
     */
    public record Kpi(Long value, Double changePct, List<Long> sparkline, String note, String changeBasis) {}

    public record MetricsResponse(
            String mode,
            String range,
            Instant windowStart,
            Instant windowEnd,
            Kpi totalEvents,
            Kpi activeRepositories,
            Kpi pushEvents,
            Kpi commits,
            Kpi pullRequests,
            Kpi forks,
            Instant generatedAt) {}

    /** Values are average events per minute within the bucket. */
    public record ActivityPoint(Instant t, double total, double push, double pullRequest) {}

    public record ActivityResponse(
            String mode, String range, int bucketMinutes, List<ActivityPoint> points, Instant generatedAt) {}

    public record ScoreWeights(double push, double pullRequest, double fork, double other) {}

    public record TrendingRepo(
            int rank,
            String name,
            String owner,
            String url,
            double score,
            double share,
            long totalEvents,
            long pushEvents,
            long pullRequestEvents,
            long forkEvents,
            long otherEvents,
            List<Long> sparkline) {}

    public record TrendingResponse(
            String mode,
            String range,
            String scoreFormula,
            ScoreWeights weights,
            String disclaimer,
            List<TrendingRepo> repositories,
            Instant generatedAt) {}

    public record BreakdownItem(EventKind kind, String label, long count, double percent) {}

    public record BreakdownResponse(
            String mode, String range, long total, List<BreakdownItem> items, Instant generatedAt) {}

    public record RisingResponse(
            String mode,
            int windowMinutes,
            int baselineWindows,
            long minEvents,
            String disclaimer,
            List<RisingRepository> repositories,
            Instant generatedAt) {}

    public record EventsResponse(String mode, List<EventView> events, Instant generatedAt) {}

    public record IngestionInfo(
            boolean reachable,
            String status,
            Instant lastSuccessfulPoll,
            Instant lastPollAttempt,
            long eventsIngested,
            String lastError,
            int consecutiveFailures,
            int pollIntervalSeconds,
            int rateLimitRemaining) {}

    public record AnalyticsInfo(
            String streamsState, boolean redis, boolean postgres, Long consumerLag, Instant lastEventObservedAt) {}

    public record DemoInfo(boolean running, Instant startedAt) {}

    public record SystemStatus(
            String mode,
            IngestionInfo ingestion,
            AnalyticsInfo analytics,
            DemoInfo demo,
            String disclaimer,
            Instant generatedAt) {}
}
