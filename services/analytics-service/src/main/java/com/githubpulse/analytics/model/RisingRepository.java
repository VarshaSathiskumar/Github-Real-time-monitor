package com.githubpulse.analytics.model;

import java.time.Instant;

/**
 * A repository whose event rate in the current window is far above its recent baseline, within the
 * observed event sample only.
 *
 * @param pctIncrease null when the baseline is zero or too small for a percentage to be meaningful
 */
public record RisingRepository(
        String repo,
        String repoUrl,
        Instant windowStart,
        int windowMinutes,
        long currentCount,
        double baseline,
        Double pctIncrease,
        String level,
        String note,
        Instant detectedAt) {}
