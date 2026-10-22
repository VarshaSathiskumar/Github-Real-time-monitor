package com.githubpulse.analytics.model;

import java.time.Instant;

/** An event as shown in the live feed. */
public record EventView(
        String id,
        EventKind kind,
        String type,
        String actor,
        String repo,
        String repoUrl,
        String description,
        String url,
        Instant createdAt,
        Instant observedAt,
        boolean demo) {}
