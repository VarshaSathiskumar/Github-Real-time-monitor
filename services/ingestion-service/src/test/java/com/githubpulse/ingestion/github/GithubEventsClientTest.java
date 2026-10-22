package com.githubpulse.ingestion.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.githubpulse.ingestion.config.IngestionProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GithubEventsClientTest {

    private HttpServer server;
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastIfNoneMatch = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/events", ex -> {
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            lastIfNoneMatch.set(ex.getRequestHeaders().getFirst("If-None-Match"));
            String query = ex.getRequestURI().getQuery();
            if (query.contains("page=9")) {
                ex.getResponseHeaders().add("X-RateLimit-Remaining", "0");
                ex.getResponseHeaders().add("X-RateLimit-Reset", "1900000000");
                ex.sendResponseHeaders(403, -1);
            } else if ("\"abc\"".equals(lastIfNoneMatch.get())) {
                ex.sendResponseHeaders(304, -1);
            } else {
                byte[] body = "[{\"id\":\"1\",\"type\":\"PushEvent\"},{\"id\":\"2\",\"type\":\"WatchEvent\"}]"
                        .getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("ETag", "\"abc\"");
                ex.getResponseHeaders().add("X-Poll-Interval", "75");
                ex.getResponseHeaders().add("X-RateLimit-Remaining", "42");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GithubEventsClient client(String token) {
        var props = new IngestionProperties(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/events",
                token,
                1,
                100,
                Duration.ofSeconds(60),
                Duration.ofMinutes(5),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                100,
                3,
                "raw",
                "dlq",
                1,
                (short) 1,
                0,
                false);
        return new GithubEventsClient(new ObjectMapper(), props);
    }

    @Test
    void parsesEventsAndHeaders() throws Exception {
        FetchResult res = client("").fetch(1, null);

        assertThat(res.ok()).isTrue();
        assertThat(res.events()).hasSize(2);
        assertThat(res.etag()).isEqualTo("\"abc\"");
        assertThat(res.pollIntervalSeconds()).isEqualTo(75);
        assertThat(res.rateLimitRemaining()).isEqualTo(42);
        assertThat(lastAuth.get()).isNull();
    }

    @Test
    void sendsConditionalRequestAndToken() throws Exception {
        FetchResult res = client("secret").fetch(1, "\"abc\"");

        assertThat(res.notModified()).isTrue();
        assertThat(res.events()).isEmpty();
        assertThat(lastAuth.get()).isEqualTo("Bearer secret");
    }

    @Test
    void detectsRateLimiting() throws Exception {
        FetchResult res = client("").fetch(9, null);

        assertThat(res.rateLimited()).isTrue();
        assertThat(res.rateLimitResetEpochSeconds()).isEqualTo(1900000000L);
    }
}
