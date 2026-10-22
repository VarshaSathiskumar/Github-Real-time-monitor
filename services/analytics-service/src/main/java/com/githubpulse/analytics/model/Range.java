package com.githubpulse.analytics.model;

import java.time.Duration;

/** Selectable observation windows and the bucket width used to chart them. */
public enum Range {
    M15("15m", 15, 1),
    H1("1h", 60, 1),
    H6("6h", 360, 5),
    H24("24h", 1440, 15);

    private final String code;
    private final int minutes;
    private final int bucketMinutes;

    Range(String code, int minutes, int bucketMinutes) {
        this.code = code;
        this.minutes = minutes;
        this.bucketMinutes = bucketMinutes;
    }

    public String code() {
        return code;
    }

    public int minutes() {
        return minutes;
    }

    public int bucketMinutes() {
        return bucketMinutes;
    }

    public Duration duration() {
        return Duration.ofMinutes(minutes);
    }

    /** Windows up to one hour are served from Redis counters, longer ones from PostgreSQL. */
    public boolean live() {
        return minutes <= 60;
    }

    public int sparklineBucketMinutes() {
        return Math.max(1, (int) Math.ceil(minutes / 30.0));
    }

    public static Range parse(String code) {
        if (code == null || code.isBlank()) {
            return H1;
        }
        for (Range r : values()) {
            if (r.code.equalsIgnoreCase(code.trim())) {
                return r;
            }
        }
        throw new IllegalArgumentException("Unsupported range '" + code + "', expected 15m, 1h, 6h or 24h");
    }
}
