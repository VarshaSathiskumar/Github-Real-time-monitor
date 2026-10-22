package com.githubpulse.analytics.ws;

import com.githubpulse.analytics.demo.DemoSimulator;
import com.githubpulse.analytics.model.EventView;
import com.githubpulse.analytics.model.Mode;
import com.githubpulse.analytics.model.Range;
import com.githubpulse.analytics.model.RisingRepository;
import com.githubpulse.analytics.service.DashboardService;
import com.githubpulse.analytics.service.SystemStatusService;
import com.githubpulse.analytics.store.EventFeed;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pushes dashboard updates every few seconds instead of per database write. Clients watching the same
 * (mode, range) share one computation per tick.
 */
@Component
public class WsBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(WsBroadcaster.class);
    private static final int FEED_BATCH = 20;

    private final DashboardWebSocketHandler handler;
    private final DashboardService dashboard;
    private final SystemStatusService status;
    private final EventFeed feed;
    private final DemoSimulator demo;
    private final Map<Mode, Set<String>> knownSpikes = new EnumMap<>(Mode.class);

    public WsBroadcaster(
            DashboardWebSocketHandler handler,
            DashboardService dashboard,
            SystemStatusService status,
            EventFeed feed,
            DemoSimulator demo) {
        this.handler = handler;
        this.dashboard = dashboard;
        this.status = status;
        this.feed = feed;
        this.demo = demo;
        for (Mode m : Mode.values()) {
            knownSpikes.put(m, new HashSet<>());
        }
    }

    @Scheduled(fixedDelayString = "${pulse.ws.interval:3s}")
    public void tick() {
        var clients = new ArrayList<>(handler.clients());
        if (clients.isEmpty()) {
            return;
        }
        if (clients.stream().anyMatch(c -> c.mode() == Mode.DEMO)) {
            demo.touch();
        }

        // New events: one batch per mode.
        for (Mode mode : Mode.values()) {
            List<EventView> events = feed.drain(mode, FEED_BATCH);
            if (!events.isEmpty()) {
                var msg = WsMessage.of("events", mode.code(), null, events);
                clients.stream().filter(c -> c.mode() == mode).forEach(c -> handler.send(c, msg));
            }
        }

        // Snapshots: one computation per distinct (mode, range).
        Map<String, List<WsClient>> groups =
                clients.stream().collect(Collectors.groupingBy(c -> c.mode().code() + "|" + c.range().code()));
        groups.forEach((key, members) -> {
            WsClient first = members.get(0);
            Mode mode = first.mode();
            Range range = first.range();
            try {
                List<WsMessage> messages = snapshotMessages(mode, range);
                for (WsClient c : members) {
                    messages.forEach(m -> handler.send(c, m));
                }
                sendAlerts(mode, members);
            } catch (RuntimeException e) {
                log.warn("Snapshot for {} failed: {}", key, e.toString());
            }
        });
    }

    /** Immediately brings a freshly subscribed client up to date. */
    public void sendSnapshot(WsClient client) {
        try {
            snapshotMessages(client.mode(), client.range()).forEach(m -> handler.send(client, m));
        } catch (RuntimeException e) {
            log.warn("Initial snapshot failed: {}", e.toString());
        }
    }

    List<WsMessage> snapshotMessages(Mode mode, Range range) {
        String m = mode.code();
        String r = range.code();
        List<WsMessage> out = new ArrayList<>();
        add(out, "kpi", m, r, () -> dashboard.metrics(mode, range));
        add(out, "activity", m, r, () -> dashboard.activity(mode, range));
        add(out, "trending", m, r, () -> dashboard.trending(mode, range));
        add(out, "breakdown", m, r, () -> dashboard.breakdown(mode, range));
        add(out, "rising", m, r, () -> dashboard.rising(mode));
        add(out, "status", m, r, () -> status.status(mode));
        return out;
    }

    private void add(List<WsMessage> out, String type, String mode, String range, Supplier<Object> data) {
        try {
            out.add(WsMessage.of(type, mode, range, data.get()));
        } catch (RuntimeException e) {
            log.warn("Cannot build '{}' message: {}", type, e.toString());
        }
    }

    private void sendAlerts(Mode mode, List<WsClient> members) {
        Set<String> known = knownSpikes.get(mode);
        List<RisingRepository> current = dashboard.rising(mode).repositories();
        Set<String> currentKeys = new HashSet<>();
        for (RisingRepository s : current) {
            String key = s.repo() + "@" + s.windowStart();
            currentKeys.add(key);
            if (known.add(key)) {
                var msg = WsMessage.of("alert", mode.code(), null, s);
                members.forEach(c -> handler.send(c, msg));
            }
        }
        known.retainAll(currentKeys);
    }
}
