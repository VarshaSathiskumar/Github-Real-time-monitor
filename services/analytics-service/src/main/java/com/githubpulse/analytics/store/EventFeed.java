package com.githubpulse.analytics.store;

import com.githubpulse.analytics.model.EventView;
import com.githubpulse.analytics.model.Mode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.springframework.stereotype.Component;

/**
 * Hand-over buffer between event persistence and the WebSocket broadcaster. Events are collected
 * and flushed to clients in batches so individual database operations are never broadcast.
 */
@Component
public class EventFeed {

    private static final int CAPACITY = 200;

    private final Map<Mode, ConcurrentLinkedDeque<EventView>> queues = new EnumMap<>(Mode.class);

    public EventFeed() {
        for (Mode m : Mode.values()) {
            queues.put(m, new ConcurrentLinkedDeque<>());
        }
    }

    public void offer(Mode mode, EventView view) {
        ConcurrentLinkedDeque<EventView> q = queues.get(mode);
        q.addFirst(view);
        while (q.size() > CAPACITY) {
            q.pollLast();
        }
    }

    /** Removes and returns up to {@code max} buffered events, newest first. */
    public List<EventView> drain(Mode mode, int max) {
        ConcurrentLinkedDeque<EventView> q = queues.get(mode);
        List<EventView> out = new ArrayList<>(Math.min(max, q.size()));
        EventView v;
        while ((v = q.pollFirst()) != null) {
            if (out.size() < max) {
                out.add(v);
            }
        }
        return out;
    }

    public void clear(Mode mode) {
        queues.get(mode).clear();
    }
}
