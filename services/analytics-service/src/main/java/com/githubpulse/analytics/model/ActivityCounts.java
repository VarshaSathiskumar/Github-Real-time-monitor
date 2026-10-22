package com.githubpulse.analytics.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.githubpulse.analytics.config.PulseProperties;

/** Additive event counters; the value type of every windowed aggregation. */
public record ActivityCounts(
        long total,
        long push,
        long pullRequest,
        long fork,
        long watch,
        long issues,
        long issueComments,
        long other,
        long commits,
        long commitReports) {

    public static final ActivityCounts ZERO = new ActivityCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    public static ActivityCounts of(ParsedEvent e) {
        return new ActivityCounts(
                1,
                e.kind() == EventKind.PUSH ? 1 : 0,
                e.kind() == EventKind.PULL_REQUEST ? 1 : 0,
                e.kind() == EventKind.FORK ? 1 : 0,
                e.kind() == EventKind.WATCH ? 1 : 0,
                e.kind() == EventKind.ISSUES ? 1 : 0,
                e.kind() == EventKind.ISSUE_COMMENT ? 1 : 0,
                e.kind() == EventKind.OTHER ? 1 : 0,
                e.kind() == EventKind.PUSH && e.commits() != null ? e.commits() : 0,
                e.kind() == EventKind.PUSH && e.commits() != null ? 1 : 0);
    }

    public ActivityCounts plus(ActivityCounts o) {
        return new ActivityCounts(
                total + o.total,
                push + o.push,
                pullRequest + o.pullRequest,
                fork + o.fork,
                watch + o.watch,
                issues + o.issues,
                issueComments + o.issueComments,
                other + o.other,
                commits + o.commits,
                commitReports + o.commitReports);
    }

    public long countOf(EventKind kind) {
        return switch (kind) {
            case PUSH -> push;
            case PULL_REQUEST -> pullRequest;
            case FORK -> fork;
            case WATCH -> watch;
            case ISSUES -> issues;
            case ISSUE_COMMENT -> issueComments;
            case OTHER -> other;
        };
    }

    /** Everything that is not a push, pull request or fork (stars, issues, comments, misc). */
    @JsonIgnore
    public long otherForScore() {
        return total - push - pullRequest - fork;
    }

    /** Activity score = push x wPush + PR x wPr + fork x wFork + other x wOther. */
    public double score(PulseProperties.Score w) {
        return push * w.push() + pullRequest * w.pullRequest() + fork * w.fork() + otherForScore() * w.other();
    }

    public String toCompact() {
        return total + "," + push + "," + pullRequest + "," + fork + "," + watch + "," + issues + ","
                + issueComments + "," + other + "," + commits + "," + commitReports;
    }

    public static ActivityCounts fromCompact(String s) {
        String[] p = s.split(",");
        if (p.length != 9 && p.length != 10) {
            throw new IllegalArgumentException("Bad compact counts: " + s);
        }
        long[] v = new long[10];
        for (int i = 0; i < p.length; i++) {
            v[i] = Long.parseLong(p[i]);
        }
        return new ActivityCounts(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9]);
    }
}
