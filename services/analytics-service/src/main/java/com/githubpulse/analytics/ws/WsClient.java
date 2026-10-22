package com.githubpulse.analytics.ws;

import com.githubpulse.analytics.model.Mode;
import com.githubpulse.analytics.model.Range;
import org.springframework.web.socket.WebSocketSession;

/** A connected dashboard and what it is currently watching. */
public class WsClient {

    private final WebSocketSession session;
    private volatile Mode mode = Mode.LIVE;
    private volatile Range range = Range.H1;

    public WsClient(WebSocketSession session) {
        this.session = session;
    }

    public WebSocketSession session() {
        return session;
    }

    public Mode mode() {
        return mode;
    }

    public Range range() {
        return range;
    }

    public void subscribe(Mode mode, Range range) {
        this.mode = mode;
        this.range = range;
    }
}
