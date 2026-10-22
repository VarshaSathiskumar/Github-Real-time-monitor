package com.githubpulse.ingestion.poll;

import com.fasterxml.jackson.databind.JsonNode;
import com.githubpulse.ingestion.config.IngestionProperties;
import com.githubpulse.ingestion.github.EventDeduplicator;
import com.githubpulse.ingestion.github.FetchResult;
import com.githubpulse.ingestion.github.GithubEventsClient;
import com.githubpulse.ingestion.kafka.EventPublisher;
import com.githubpulse.ingestion.status.IngestionStatusService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Self-rescheduling poll loop over GitHub's public events endpoint.
 *
 * <p>Honours X-Poll-Interval, ETag conditional requests and rate limit headers. ETags are only
 * committed after every new event of the cycle has been acknowledged by Kafka.
 */
@Component
public class PollingService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PollingService.class);

    private final GithubEventsClient client;
    private final EventDeduplicator dedup;
    private final EventPublisher publisher;
    private final IngestionStatusService status;
    private final IngestionProperties props;

    private final Map<Integer, String> etags = new HashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "github-poller"));
    private volatile boolean running;
    private int failureStreak;

    public PollingService(
            GithubEventsClient client,
            EventDeduplicator dedup,
            EventPublisher publisher,
            IngestionStatusService status,
            IngestionProperties props) {
        this.client = client;
        this.dedup = dedup;
        this.publisher = publisher;
        this.status = status;
        this.props = props;
    }

    @Override
    public void start() {
        running = true;
        if (!props.pollingEnabled()) {
            log.warn("Polling disabled (pulse.ingestion.polling-enabled=false)");
            return;
        }
        log.info(
                "Starting GitHub poller: pages={}, perPage={}, authenticated={}",
                props.effectivePages(),
                props.perPage(),
                props.hasToken());
        scheduler.schedule(this::cycle, 1, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        running = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        status.stopped();
        log.info("GitHub poller stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void cycle() {
        if (!running) {
            return;
        }
        Duration next;
        try {
            next = pollOnce();
            failureStreak = 0;
        } catch (RateLimitedException e) {
            status.rateLimited(e.getMessage(), e.remaining);
            next = e.wait;
            log.warn("Rate limited by GitHub, waiting {}s", next.toSeconds());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            failureStreak++;
            status.pollFailed(e.getClass().getSimpleName() + ": " + e.getMessage());
            next = backoff(failureStreak);
            log.error("Poll failed (streak={}), retrying in {}s", failureStreak, next.toSeconds(), e);
        }
        if (running) {
            try {
                scheduler.schedule(this::cycle, next.toMillis(), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // shutting down
            }
        }
    }

    /** Runs one polling cycle and returns the delay before the next one. */
    Duration pollOnce() throws Exception {
        status.pollAttempted();
        List<JsonNode> fresh = new ArrayList<>();
        Map<Integer, String> pendingEtags = new HashMap<>();
        int pollInterval = (int) props.minPollInterval().toSeconds();
        Integer remaining = null;
        Long resetAt = null;

        for (int page = 1; page <= props.effectivePages(); page++) {
            FetchResult res = client.fetch(page, etags.get(page));
            if (res.pollIntervalSeconds() != null) {
                pollInterval = Math.max(pollInterval, res.pollIntervalSeconds());
            }
            if (res.rateLimitRemaining() != null) {
                remaining = res.rateLimitRemaining();
                resetAt = res.rateLimitResetEpochSeconds();
            }
            if (res.rateLimited()) {
                throw new RateLimitedException(
                        "GitHub rate limit hit (HTTP " + res.statusCode() + ")", rateLimitWait(res), remaining);
            }
            if (res.notModified()) {
                if (page == 1) {
                    break; // nothing new on the newest page means nothing new on older pages
                }
                continue;
            }
            if (!res.ok()) {
                throw new IllegalStateException("GitHub returned HTTP " + res.statusCode() + ": " + res.errorBody());
            }
            if (res.etag() != null) {
                pendingEtags.put(page, res.etag());
            }
            for (JsonNode e : res.events()) {
                if (!dedup.alreadySeen(e.path("id").asText(null))) {
                    fresh.add(e);
                }
            }
            if (res.events().size() < props.perPage()) {
                break;
            }
        }

        // GitHub returns newest first; publish oldest first so downstream sees chronological order.
        Collections.reverse(fresh);
        int published = fresh.isEmpty() ? 0 : publisher.publishAll(fresh);
        fresh.forEach(e -> dedup.markSeen(e.path("id").asText(null)));
        etags.putAll(pendingEtags);

        status.pollSucceeded(published, pollInterval, remaining);
        log.info("Poll ok: {} new events, next poll in {}s, rateRemaining={}", published, pollInterval, remaining);

        Duration delay = Duration.ofSeconds(pollInterval);
        if (remaining != null && remaining <= props.rateLimitReserve() && resetAt != null) {
            Duration untilReset = Duration.between(Instant.now(), Instant.ofEpochSecond(resetAt)).plusSeconds(1);
            if (untilReset.compareTo(delay) > 0) {
                log.warn("Rate limit nearly exhausted ({} left), pausing until reset", remaining);
                delay = untilReset;
            }
        }
        return delay;
    }

    private Duration rateLimitWait(FetchResult res) {
        if (res.retryAfterSeconds() != null) {
            return Duration.ofSeconds(res.retryAfterSeconds() + 1);
        }
        if (res.rateLimitResetEpochSeconds() != null) {
            Duration d = Duration.between(Instant.now(), Instant.ofEpochSecond(res.rateLimitResetEpochSeconds()));
            return d.isNegative() ? props.minPollInterval() : d.plusSeconds(1);
        }
        return Duration.ofMinutes(1);
    }

    Duration backoff(int streak) {
        long base = props.minPollInterval().toMillis() * (1L << Math.min(streak, 6));
        long capped = Math.min(base, props.maxBackoff().toMillis());
        long jitter = ThreadLocalRandom.current().nextLong(capped / 5 + 1);
        return Duration.ofMillis(capped + jitter);
    }

    static final class RateLimitedException extends RuntimeException {
        final transient Duration wait;
        final Integer remaining;

        RateLimitedException(String message, Duration wait, Integer remaining) {
            super(message);
            this.wait = wait;
            this.remaining = remaining;
        }
    }
}
