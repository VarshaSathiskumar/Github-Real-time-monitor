package com.githubpulse.ingestion.github;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Outcome of one GET against the GitHub events endpoint. */
public record FetchResult(
        int statusCode,
        List<JsonNode> events,
        String etag,
        Integer pollIntervalSeconds,
        Integer rateLimitRemaining,
        Long rateLimitResetEpochSeconds,
        Long retryAfterSeconds,
        String errorBody) {

    public boolean notModified() {
        return statusCode == 304;
    }

    public boolean ok() {
        return statusCode == 200;
    }

    public boolean rateLimited() {
        return statusCode == 429
                || (statusCode == 403 && (retryAfterSeconds != null || Integer.valueOf(0).equals(rateLimitRemaining)));
    }
}
