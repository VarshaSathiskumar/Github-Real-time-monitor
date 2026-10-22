package com.githubpulse.analytics.service;

import com.githubpulse.analytics.model.ActivityCounts;
import com.githubpulse.analytics.model.MinuteCounts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Groups per-minute counts into epoch-aligned buckets of N minutes, filling empty buckets with zero. */
public final class Buckets {

    private Buckets() {}

    public record Bucket(Instant start, ActivityCounts counts) {}

    public static List<Bucket> aggregate(List<MinuteCounts> minutes, Instant from, Instant to, int bucketMinutes) {
        long first = Math.floorDiv(from.getEpochSecond() / 60, (long) bucketMinutes) * bucketMinutes;
        long last = Math.floorDiv(to.minusSeconds(1).getEpochSecond(), 60L);
        Map<Long, ActivityCounts> sums = new HashMap<>();
        for (MinuteCounts m : minutes) {
            long minute = m.minute().getEpochSecond() / 60;
            if (minute < first || minute > last) {
                continue;
            }
            long bucket = Math.floorDiv(minute, (long) bucketMinutes) * bucketMinutes;
            sums.merge(bucket, m.counts(), ActivityCounts::plus);
        }
        List<Bucket> out = new ArrayList<>();
        for (long b = first; b <= last; b += bucketMinutes) {
            out.add(new Bucket(Instant.ofEpochSecond(b * 60), sums.getOrDefault(b, ActivityCounts.ZERO)));
        }
        return out;
    }

    public static ActivityCounts sum(List<MinuteCounts> minutes) {
        ActivityCounts total = ActivityCounts.ZERO;
        for (MinuteCounts m : minutes) {
            total = total.plus(m.counts());
        }
        return total;
    }
}
