package com.githubpulse.analytics.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.githubpulse.analytics.demo.DemoSimulator;
import com.githubpulse.analytics.model.EventKind;
import com.githubpulse.analytics.model.EventView;
import com.githubpulse.analytics.model.Mode;
import com.githubpulse.analytics.model.Range;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class WebSocketMessagingTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private DemoSimulator demo;
    private WsBroadcaster broadcaster;
    private DashboardWebSocketHandler handler;
    private WebSocketSession session;

    @BeforeEach
    void setUp() {
        demo = mock(DemoSimulator.class);
        broadcaster = mock(WsBroadcaster.class);
        handler = new DashboardWebSocketHandler(mapper, demo, broadcaster);
        session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);
    }

    private JsonNode lastSent() throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());
        return mapper.readTree(captor.getValue().getPayload());
    }

    @Test
    void messageEnvelopeIsStableJson() throws Exception {
        EventView view = new EventView(
                "1", EventKind.PUSH, "PushEvent", "alice", "octo/repo", "https://github.com/octo/repo",
                "alice pushed to octo/repo", "https://github.com/octo/repo", Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:05Z"), false);
        WsMessage msg = new WsMessage("events", "live", null, Instant.parse("2026-01-01T00:00:06Z"), List.of(view));

        JsonNode json = mapper.readTree(mapper.writeValueAsString(msg));

        assertThat(json.get("type").asText()).isEqualTo("events");
        assertThat(json.get("mode").asText()).isEqualTo("live");
        assertThat(json.has("range")).isTrue();
        assertThat(json.get("ts").asText()).isEqualTo("2026-01-01T00:00:06Z");
        JsonNode event = json.get("data").get(0);
        assertThat(event.get("kind").asText()).isEqualTo("PUSH");
        assertThat(event.get("createdAt").asText()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(event.get("demo").asBoolean()).isFalse();
    }

    @Test
    void newConnectionReceivesHelloAndDefaultsToLiveOneHour() throws Exception {
        handler.afterConnectionEstablished(session);

        assertThat(lastSent().get("type").asText()).isEqualTo("hello");
        assertThat(handler.clients()).hasSize(1);
        WsClient c = handler.clients().iterator().next();
        assertThat(c.mode()).isEqualTo(Mode.LIVE);
        assertThat(c.range()).isEqualTo(Range.H1);
    }

    @Test
    void subscribeSwitchesModeAndRangeAndStartsDemoOnDemand() throws Exception {
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new TextMessage("{\"action\":\"subscribe\",\"mode\":\"demo\",\"range\":\"6h\"}"));

        WsClient c = handler.clients().iterator().next();
        assertThat(c.mode()).isEqualTo(Mode.DEMO);
        assertThat(c.range()).isEqualTo(Range.H6);
        verify(demo).ensureRunning();
        verify(broadcaster).sendSnapshot(c);
    }

    @Test
    void subscribingToLiveDoesNotStartTheSimulator() throws Exception {
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new TextMessage("{\"action\":\"subscribe\",\"mode\":\"live\",\"range\":\"15m\"}"));

        verify(demo, never()).ensureRunning();
        assertThat(handler.clients().iterator().next().range()).isEqualTo(Range.M15);
    }

    @Test
    void invalidMessagesAreIgnored() throws Exception {
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new TextMessage("garbage"));
        handler.handleMessage(session, new TextMessage("{\"action\":\"subscribe\",\"range\":\"99y\"}"));

        verify(broadcaster, never()).sendSnapshot(any());
        assertThat(handler.clients().iterator().next().range()).isEqualTo(Range.H1);
    }

    @Test
    void closedSessionsAreForgotten() throws Exception {
        handler.afterConnectionEstablished(session);
        handler.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL);

        assertThat(handler.clients()).isEmpty();
    }
}
