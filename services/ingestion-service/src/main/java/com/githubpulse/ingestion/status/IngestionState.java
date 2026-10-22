package com.githubpulse.ingestion.status;

public enum IngestionState {
    STARTING,
    HEALTHY,
    DEGRADED,
    RATE_LIMITED,
    FAILING,
    STOPPED
}
