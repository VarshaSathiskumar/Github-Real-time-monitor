package com.githubpulse.ingestion.status;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** Thread-safe holder of ingestion health, exposed over gRPC and actuator. */
@Component
public class IngestionStatusService {

    /** Consecutive failures before the state moves from DEGRADED to FAILING. */
    static final int FAILING_THRESHOLD = 3;

    private final AtomicLong eventsIngested = new AtomicLong();
    private volatile IngestionState state = IngestionState.STARTING;
    private volatile Instant lastSuccessfulPoll;
    private volatile Instant lastPollAttempt;
    private volatile String lastError = "";
    private volatile int consecutiveFailures;
    private volatile int pollIntervalSeconds;
    private volatile int rateLimitRemaining = -1;

    public void pollAttempted() {
        lastPollAttempt = Instant.now();
    }

    public void pollSucceeded(int published, int pollIntervalSeconds, Integer rateLimitRemaining) {
        eventsIngested.addAndGet(published);
        lastSuccessfulPoll = Instant.now();
        lastError = "";
        consecutiveFailures = 0;
        this.pollIntervalSeconds = pollIntervalSeconds;
        if (rateLimitRemaining != null) {
            this.rateLimitRemaining = rateLimitRemaining;
        }
        state = IngestionState.HEALTHY;
    }

    public void pollFailed(String error) {
        consecutiveFailures++;
        lastError = error;
        state = consecutiveFailures >= FAILING_THRESHOLD ? IngestionState.FAILING : IngestionState.DEGRADED;
    }

    public void rateLimited(String error, Integer rateLimitRemaining) {
        lastError = error;
        consecutiveFailures++;
        if (rateLimitRemaining != null) {
            this.rateLimitRemaining = rateLimitRemaining;
        }
        state = IngestionState.RATE_LIMITED;
    }

    public void stopped() {
        state = IngestionState.STOPPED;
    }

    public Snapshot snapshot() {
        return new Snapshot(
                state,
                lastSuccessfulPoll,
                lastPollAttempt,
                eventsIngested.get(),
                lastError,
                consecutiveFailures,
                pollIntervalSeconds,
                rateLimitRemaining);
    }

    public record Snapshot(
            IngestionState state,
            Instant lastSuccessfulPoll,
            Instant lastPollAttempt,
            long eventsIngested,
            String lastError,
            int consecutiveFailures,
            int pollIntervalSeconds,
            int rateLimitRemaining) {}
}
