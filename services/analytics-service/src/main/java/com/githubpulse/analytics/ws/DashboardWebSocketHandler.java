package com.githubpulse.analytics.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.githubpulse.analytics.demo.DemoSimulator;
import com.githubpulse.analytics.model.Mode;
import com.githubpulse.analytics.model.Range;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Raw WebSocket endpoint {@code /ws}. Clients send {@code {"action":"subscribe","mode":"live|demo","range":"1h"}}
 * and receive throttled snapshots from {@link WsBroadcaster}.
 */
@Component
public class DashboardWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DashboardWebSocketHandler.class);

    private final Map<String, WsClient> clients = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final DemoSimulator demo;
    private final WsBroadcaster broadcaster;

    public DashboardWebSocketHandler(ObjectMapper mapper, DemoSimulator demo, @Lazy WsBroadcaster broadcaster) {
        this.mapper = mapper;
        this.demo = demo;
        this.broadcaster = broadcaster;
    }

    public Collection<WsClient> clients() {
        return clients.values();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) {
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, 5_000, 512 * 1024);
        WsClient client = new WsClient(session);
        clients.put(raw.getId(), client);
        send(client, WsMessage.of("hello", client.mode().code(), client.range().code(), Map.of("serverTime", java.time.Instant.now())));
        log.info("WebSocket connected: {} (total {})", raw.getId(), clients.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) {
        WsClient client = clients.get(raw.getId());
        if (client == null) {
            return;
        }
        try {
            JsonNode json = mapper.readTree(message.getPayload());
            if ("subscribe".equals(json.path("action").asText())) {
                Mode mode = Mode.parse(json.path("mode").asText("live"));
                Range range = Range.parse(json.path("range").asText("1h"));
                client.subscribe(mode, range);
                if (mode == Mode.DEMO) {
                    demo.ensureRunning();
                }
                broadcaster.sendSnapshot(client);
            }
        } catch (IllegalArgumentException | IOException e) {
            log.debug("Ignoring invalid WebSocket message: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        clients.remove(session.getId());
        log.info("WebSocket closed: {} ({}), remaining {}", session.getId(), status, clients.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WebSocket transport error on {}: {}", session.getId(), exception.toString());
        clients.remove(session.getId());
    }

    /** Serialises and sends one message; a failing client is dropped. */
    public void send(WsClient client, WsMessage message) {
        try {
            if (client.session().isOpen()) {
                client.session().sendMessage(new TextMessage(mapper.writeValueAsString(message)));
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Dropping WebSocket client {}: {}", client.session().getId(), e.toString());
            clients.remove(client.session().getId());
            try {
                client.session().close(CloseStatus.SESSION_NOT_RELIABLE);
            } catch (IOException ignored) {
                // already gone
            }
        }
    }
}
