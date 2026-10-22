package com.githubpulse.analytics.store;

import java.time.Instant;

/**
 * Redis layout (all keys expire; Redis is a speed layer, PostgreSQL is the durable record):
 *
 * <ul>
 *   <li>{@code pulse:min:<epochMinute>} string, compact {@link com.githubpulse.analytics.model.ActivityCounts}
 *   <li>{@code pulse:repomin:<epochMinute>} hash, repository to compact counts
 *   <li>{@code pulse:repos:<epochMinute>} HyperLogLog of repositories seen in that minute
 *   <li>{@code pulse:recent} list of the latest events as JSON, newest first
 *   <li>{@code pulse:rising} hash, repository to spike JSON
 * </ul>
 */
public final class RedisKeys {

    public static final String RECENT = "pulse:recent";
    public static final String RISING = "pulse:rising";

    private RedisKeys() {}

    public static long epochMinute(Instant t) {
        return Math.floorDiv(t.getEpochSecond(), 60L);
    }

    public static String minute(long epochMinute) {
        return "pulse:min:" + epochMinute;
    }

    public static String repoMinute(long epochMinute) {
        return "pulse:repomin:" + epochMinute;
    }

    public static String repoSet(long epochMinute) {
        return "pulse:repos:" + epochMinute;
    }
}
