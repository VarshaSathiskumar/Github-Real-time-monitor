package com.githubpulse.analytics.model;

import java.time.Instant;

public record MinuteCounts(Instant minute, ActivityCounts counts) {}
