package com.githubpulse.analytics.model;

public enum EventKind {
    PUSH("Push events"),
    PULL_REQUEST("Pull requests"),
    FORK("Forks"),
    WATCH("Stars"),
    ISSUES("Issues"),
    ISSUE_COMMENT("Issue comments"),
    OTHER("Other events");

    private final String label;

    EventKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static EventKind fromGithubType(String type) {
        return switch (type) {
            case "PushEvent" -> PUSH;
            case "PullRequestEvent" -> PULL_REQUEST;
            case "ForkEvent" -> FORK;
            case "WatchEvent" -> WATCH;
            case "IssuesEvent" -> ISSUES;
            case "IssueCommentEvent" -> ISSUE_COMMENT;
            default -> OTHER;
        };
    }
}
