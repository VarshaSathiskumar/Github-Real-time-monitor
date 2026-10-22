package com.githubpulse.analytics.store;

import com.githubpulse.analytics.config.PulseProperties;
import java.sql.Timestamp;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps PostgreSQL bounded by deleting rows older than the configured retention. */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final JdbcTemplate jdbc;
    private final PulseProperties.Retention retention;

    public RetentionJob(JdbcTemplate jdbc, PulseProperties props) {
        this.jdbc = jdbc;
        this.retention = props.retention();
    }

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT1H")
    public void run() {
        Instant now = Instant.now();
        try {
            int events = purge("github_events", "observed_at", now.minus(retention.events()));
            int repoMinutes = purge("repo_minute_aggregates", "minute", now.minus(retention.repoMinutes()));
            int minutes = purge("minute_aggregates", "minute", now.minus(retention.minutes()));
            int rising = purge("rising_repositories", "window_start", now.minus(retention.rising()));
            int failures = purge("processing_failures", "failed_at", now.minus(retention.failures()));
            log.info(
                    "Retention: removed events={}, repoMinutes={}, minutes={}, rising={}, failures={}",
                    events, repoMinutes, minutes, rising, failures);
        } catch (RuntimeException e) {
            log.error("Retention job failed", e);
        }
    }

    int purge(String table, String column, Instant cutoff) {
        return jdbc.update("DELETE FROM " + table + " WHERE " + column + " < ?", Timestamp.from(cutoff));
    }
}
