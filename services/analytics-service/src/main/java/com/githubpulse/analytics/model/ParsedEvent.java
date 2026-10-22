package com.githubpulse.analytics.model;

import java.time.Instant;

/**
 * The few fields of a GitHub event the dashboard needs.
 *
 * @param createdAt when GitHub says the event happened (display only)
 * @param ingestedAt when the ingestion service published it
 * @param observedAt Kafka record timestamp; the clock all windows and aggregates are based on
 */
public record ParsedEvent(
        long id,
        String type,
        EventKind kind,
        String actor,
        long repoId,
        String repoName,
        String repoUrl,
        Integer commits,
        String action,
        String description,
        String url,
        Instant createdAt,
        Instant ingestedAt,
        Instant observedAt) {

    public ParsedEvent withObservedAt(Instant observed) {
        return new ParsedEvent(
                id, type, kind, actor, repoId, repoName, repoUrl, commits, action, description, url, createdAt,
                ingestedAt, observed);
    }

    public String owner() {
        int slash = repoName.indexOf('/');
        return slash > 0 ? repoName.substring(0, slash) : repoName;
    }

    public EventView toView(boolean demo) {
        return new EventView(
                Long.toString(id), kind, type, actor, repoName, repoUrl, description, url, createdAt, observedAt, demo);
    }
}
