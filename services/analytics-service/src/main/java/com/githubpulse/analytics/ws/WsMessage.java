package com.githubpulse.analytics.ws;

import java.time.Instant;

/**
 * Envelope of every server-to-client WebSocket message.
 *
 * <p>Types: hello, kpi, activity, trending, breakdown, rising, events, alert, status.
 */
public record WsMessage(String type, String mode, String range, Instant ts, Object data) {

    public static WsMessage of(String type, String mode, String range, Object data) {
        return new WsMessage(type, mode, range, Instant.now(), data);
    }
}
