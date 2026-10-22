package com.githubpulse.ingestion.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.githubpulse.ingestion.config.IngestionProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Thin client for GET /events. Sends ETags and the optional token, and surfaces rate limit headers. */
@Component
public class GithubEventsClient {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final IngestionProperties props;

    public GithubEventsClient(ObjectMapper mapper, IngestionProperties props) {
        this.mapper = mapper;
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public FetchResult fetch(int page, String etag) throws IOException, InterruptedException {
        URI uri = URI.create(props.githubApiUrl() + "?per_page=" + props.perPage() + "&page=" + page);
        HttpRequest.Builder req = HttpRequest.newBuilder(uri)
                .timeout(props.requestTimeout())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "GitHubPulse-Ingestion")
                .GET();
        if (etag != null) {
            req.header("If-None-Match", etag);
        }
        if (props.hasToken()) {
            req.header("Authorization", "Bearer " + props.githubToken());
        }

        HttpResponse<String> res = http.send(req.build(), BodyHandlers.ofString());
        return toResult(res);
    }

    FetchResult toResult(HttpResponse<String> res) throws IOException {
        var h = res.headers();
        String etag = h.firstValue("ETag").orElse(null);
        Integer poll = h.firstValue("X-Poll-Interval").map(GithubEventsClient::parseInt).orElse(null);
        Integer remaining = h.firstValue("X-RateLimit-Remaining").map(GithubEventsClient::parseInt).orElse(null);
        Long reset = h.firstValue("X-RateLimit-Reset").map(GithubEventsClient::parseLong).orElse(null);
        Long retryAfter = h.firstValue("Retry-After").map(GithubEventsClient::parseLong).orElse(null);

        if (res.statusCode() == 200) {
            JsonNode root = mapper.readTree(res.body());
            List<JsonNode> events = new ArrayList<>();
            if (root.isArray()) {
                root.forEach(events::add);
            }
            return new FetchResult(200, events, etag, poll, remaining, reset, retryAfter, null);
        }
        String body = Optional.ofNullable(res.body()).orElse("");
        if (body.length() > 300) {
            body = body.substring(0, 300);
        }
        return new FetchResult(res.statusCode(), List.of(), etag, poll, remaining, reset, retryAfter, body);
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLong(String s) {
        try {
            return Long.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
