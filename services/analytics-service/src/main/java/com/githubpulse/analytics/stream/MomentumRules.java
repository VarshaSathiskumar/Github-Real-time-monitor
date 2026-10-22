package com.githubpulse.analytics.stream;

import com.githubpulse.analytics.config.PulseProperties;
import com.githubpulse.analytics.model.RisingRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Pure spike detection rule shared by the Kafka Streams processor and the demo simulator.
 *
 * <p>A repository is flagged when the event count in the current window reaches {@code minEvents}
 * and exceeds {@code spikeRatio} times its baseline (mean of the preceding windows). When the
 * baseline is below {@code minBaseline} no percentage is reported, because a percentage over a
 * near-zero denominator is misleading; the repository is labelled a BURST instead.
 */
public final class MomentumRules {

    private MomentumRules() {}

    /**
     * @param history window start (epoch millis) to event count, for windows before and including the current one
     * @param firstWindowStart start of the very first window this processor ever observed (epoch millis)
     */
    public static Optional<RisingRepository> evaluate(
            PulseProperties.Momentum cfg,
            String repo,
            long windowStart,
            long currentCount,
            Map<Long, Long> history,
            long firstWindowStart,
            Instant detectedAt) {
        long sizeMs = cfg.window().toMillis();
        long completeWindowsObserved = Math.max(0, (windowStart - firstWindowStart) / sizeMs);
        if (completeWindowsObserved < cfg.minHistoryWindows() || currentCount < cfg.minEvents()) {
            return Optional.empty();
        }

        int n = (int) Math.min(cfg.baselineWindows(), completeWindowsObserved);
        long sum = 0;
        for (int i = 1; i <= n; i++) {
            sum += history.getOrDefault(windowStart - i * sizeMs, 0L);
        }
        double baseline = (double) sum / n;

        boolean baselineTooSmall = baseline < cfg.minBaseline();
        if (!baselineTooSmall && currentCount < cfg.spikeRatio() * baseline) {
            return Optional.empty();
        }

        Double pct = baselineTooSmall ? null : round1((currentCount - baseline) / baseline * 100.0);
        String level;
        String note;
        if (baselineTooSmall) {
            level = "BURST";
            note = "Almost no earlier activity in the observed sample, so no percentage is shown.";
        } else if (pct >= 500) {
            level = "SURGING";
            note = "Activity spike in the observed event sample.";
        } else if (pct >= 200) {
            level = "RISING";
            note = "Activity spike in the observed event sample.";
        } else {
            level = "WARMING";
            note = "Activity spike in the observed event sample.";
        }
        return Optional.of(new RisingRepository(
                repo,
                "https://github.com/" + repo,
                Instant.ofEpochMilli(windowStart),
                (int) cfg.window().toMinutes(),
                currentCount,
                round1(baseline),
                pct,
                level,
                note,
                detectedAt));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
