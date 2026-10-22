package com.githubpulse.ingestion.poll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.githubpulse.ingestion.config.IngestionProperties;
import com.githubpulse.ingestion.github.EventDeduplicator;
import com.githubpulse.ingestion.github.FetchResult;
import com.githubpulse.ingestion.github.GithubEventsClient;
import com.githubpulse.ingestion.kafka.EventPublisher;
import com.githubpulse.ingestion.status.IngestionState;
import com.githubpulse.ingestion.status.IngestionStatusService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PollingServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GithubEventsClient client;
    private EventPublisher publisher;
    private IngestionStatusService status;
    private PollingService service;

    @BeforeEach
    void setUp() {
        client = mock(GithubEventsClient.class);
        publisher = mock(EventPublisher.class);
        status = new IngestionStatusService();
        var props = new IngestionProperties(
                "http://unused",
                "",
                1,
                100,
                Duration.ofSeconds(60),
                Duration.ofMinutes(5),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                1000,
                3,
                "raw",
                "dlq",
                1,
                (short) 1,
                0,
                false);
        service = new PollingService(client, new EventDeduplicator(1000), publisher, status, props);
    }

    private static JsonNode event(String id) {
        return MAPPER.createObjectNode().put("id", id).put("type", "PushEvent");
    }

    private static FetchResult ok(String etag, int poll, String... ids) {
        return new FetchResult(200, java.util.Arrays.stream(ids).map(PollingServiceTest::event).toList(), etag, poll, 50, null, null, null);
    }

    @Test
    void publishesOldestFirstAndHonoursPollInterval() throws Exception {
        when(client.fetch(eq(1), isNull())).thenReturn(ok("\"e1\"", 90, "3", "2", "1"));
        when(publisher.publishAll(any())).thenReturn(3);

        Duration next = service.pollOnce();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JsonNode>> captor = ArgumentCaptor.forClass(List.class);
        verify(publisher).publishAll(captor.capture());
        assertThat(captor.getValue()).extracting(n -> n.get("id").asText()).containsExactly("1", "2", "3");
        assertThat(next).isEqualTo(Duration.ofSeconds(90));
        var snap = status.snapshot();
        assertThat(snap.state()).isEqualTo(IngestionState.HEALTHY);
        assertThat(snap.eventsIngested()).isEqualTo(3);
        assertThat(snap.lastSuccessfulPoll()).isNotNull();
    }

    @Test
    void secondPollSendsEtagAndSkipsDuplicates() throws Exception {
        when(client.fetch(eq(1), isNull())).thenReturn(ok("\"e1\"", 60, "2", "1"));
        when(client.fetch(eq(1), eq("\"e1\""))).thenReturn(ok("\"e2\"", 60, "3", "2", "1"));
        when(publisher.publishAll(any())).thenAnswer(i -> ((List<?>) i.getArgument(0)).size());

        service.pollOnce();
        service.pollOnce();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JsonNode>> captor = ArgumentCaptor.forClass(List.class);
        verify(publisher, times(2)).publishAll(captor.capture());
        assertThat(captor.getAllValues().get(1)).extracting(n -> n.get("id").asText()).containsExactly("3");
        assertThat(status.snapshot().eventsIngested()).isEqualTo(3);
    }

    @Test
    void notModifiedPublishesNothingButCountsAsSuccessfulPoll() throws Exception {
        when(client.fetch(anyInt(), any())).thenReturn(new FetchResult(304, List.of(), null, 60, 55, null, null, null));

        service.pollOnce();

        verify(publisher, never()).publishAll(any());
        assertThat(status.snapshot().lastSuccessfulPoll()).isNotNull();
        assertThat(status.snapshot().state()).isEqualTo(IngestionState.HEALTHY);
    }

    @Test
    void failedPublishDoesNotAdvanceEtagSoEventsAreRetried() throws Exception {
        when(client.fetch(eq(1), isNull())).thenReturn(ok("\"e1\"", 60, "1"));
        when(publisher.publishAll(any()))
                .thenThrow(new EventPublisher.PublishException("broker down", new RuntimeException()))
                .thenReturn(1);

        assertThatThrownBy(service::pollOnce).isInstanceOf(EventPublisher.PublishException.class);
        service.pollOnce();

        // The conditional header was never committed, so the second attempt is unconditional again.
        verify(client, times(2)).fetch(eq(1), isNull());
        verify(publisher, times(2)).publishAll(any());
    }

    @Test
    void rateLimitedResponseRaisesWaitAndUpdatesStatus() throws Exception {
        when(client.fetch(anyInt(), any())).thenReturn(new FetchResult(403, List.of(), null, null, 0, null, 120L, "limit"));

        assertThatThrownBy(service::pollOnce).isInstanceOf(PollingService.RateLimitedException.class);
    }

    @Test
    void backoffGrowsAndIsCapped() {
        assertThat(service.backoff(1)).isGreaterThanOrEqualTo(Duration.ofSeconds(120));
        assertThat(service.backoff(20)).isLessThanOrEqualTo(Duration.ofMinutes(5).plus(Duration.ofMinutes(1)));
    }
}
