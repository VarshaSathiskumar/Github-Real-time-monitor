package com.githubpulse.analytics.demo;

import com.githubpulse.analytics.config.PulseProperties;
import com.githubpulse.analytics.model.ActivityCounts;
import com.githubpulse.analytics.model.EventKind;
import com.githubpulse.analytics.model.EventView;
import com.githubpulse.analytics.model.MinuteCounts;
import com.githubpulse.analytics.model.Mode;
import com.githubpulse.analytics.model.Range;
import com.githubpulse.analytics.model.RisingRepository;
import com.githubpulse.analytics.store.ActivityStore;
import com.githubpulse.analytics.store.DashboardSource;
import com.githubpulse.analytics.stream.MomentumRules;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * In-memory home of simulated activity. It is entirely separate from Redis and PostgreSQL, so demo
 * events can never leak into real metrics.
 */
@Component
public class DemoDataStore implements DashboardSource, ActivityStore {

    private static final int RECENT_MAX = 100;

    private static final class MinuteBucket {
        ActivityCounts counts = ActivityCounts.ZERO;
        final Map<String, ActivityCounts> repos = new HashMap<>();
    }

    private final NavigableMap<Long, MinuteBucket> minutes = new TreeMap<>();
    private final Deque<EventView> recent = new ArrayDeque<>();
    private final PulseProperties.Momentum momentum;

    public DemoDataStore(PulseProperties props) {
        PulseProperties.Momentum m = props.momentum();
        // Demo starts with six hours of synthetic history, so one baseline window is enough to compare against.
        this.momentum = new PulseProperties.Momentum(
                m.window(), m.baselineWindows(), 1, Math.min(m.minEvents(), 8), m.spikeRatio(), m.minBaseline(),
                m.visibleFor());
    }

    public synchronized void add(Instant at, EventKind kind, String repo, int commits) {
        long minute = at.getEpochSecond() / 60;
        MinuteBucket b = minutes.computeIfAbsent(minute, k -> new MinuteBucket());
        ActivityCounts one = new ActivityCounts(
                1,
                kind == EventKind.PUSH ? 1 : 0,
                kind == EventKind.PULL_REQUEST ? 1 : 0,
                kind == EventKind.FORK ? 1 : 0,
                kind == EventKind.WATCH ? 1 : 0,
                kind == EventKind.ISSUES ? 1 : 0,
                kind == EventKind.ISSUE_COMMENT ? 1 : 0,
                kind == EventKind.OTHER ? 1 : 0,
                kind == EventKind.PUSH ? commits : 0,
                kind == EventKind.PUSH ? 1 : 0);
        b.counts = b.counts.plus(one);
        b.repos.merge(repo, one, ActivityCounts::plus);
    }

    public synchronized void addRecent(EventView view) {
        recent.addFirst(view);
        while (recent.size() > RECENT_MAX) {
            recent.removeLast();
        }
    }

    public synchronized void clear() {
        minutes.clear();
        recent.clear();
    }

    public synchronized void pruneOlderThan(Instant cutoff) {
        minutes.headMap(cutoff.getEpochSecond() / 60).clear();
    }

    // DashboardSource

    @Override
    public Mode mode() {
        return Mode.DEMO;
    }

    @Override
    public Instant now() {
        return Instant.now();
    }

    @Override
    public ActivityStore store(Range range) {
        return this;
    }

    @Override
    public synchronized Optional<Instant> observationStart() {
        return minutes.isEmpty() ? Optional.empty() : Optional.of(Instant.ofEpochSecond(minutes.firstKey() * 60));
    }

    @Override
    public synchronized List<EventView> recentEvents(int limit) {
        return recent.stream().limit(limit).toList();
    }

    @Override
    public synchronized List<RisingRepository> rising() {
        if (minutes.isEmpty()) {
            return List.of();
        }
        int size = (int) momentum.window().toMinutes();
        long nowMinute = Instant.now().getEpochSecond() / 60;
        long currentWindow = Math.floorDiv(nowMinute, (long) size) * size;
        long firstWindow = Math.floorDiv(minutes.firstKey(), (long) size) * size;

        Map<String, Long> currentCounts = new HashMap<>();
        minutes.subMap(currentWindow, true, nowMinute, true)
                .values()
                .forEach(b -> b.repos.forEach((repo, c) -> currentCounts.merge(repo, c.total(), Long::sum)));

        long sizeMs = momentum.window().toMillis();
        List<RisingRepository> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : currentCounts.entrySet()) {
            if (e.getValue() < momentum.minEvents()) {
                continue;
            }
            Map<Long, Long> history = new HashMap<>();
            for (int i = 1; i <= momentum.baselineWindows(); i++) {
                long ws = currentWindow - (long) i * size;
                long count = 0;
                for (MinuteBucket b : minutes.subMap(ws, true, ws + size - 1, true).values()) {
                    ActivityCounts c = b.repos.get(e.getKey());
                    count += c == null ? 0 : c.total();
                }
                history.put(ws * 60_000L, count);
            }
            MomentumRules.evaluate(
                            momentum,
                            e.getKey(),
                            currentWindow * 60_000L,
                            e.getValue(),
                            history,
                            firstWindow * 60_000L,
                            Instant.now())
                    .ifPresent(out::add);
        }
        out.sort(Comparator.comparingLong(RisingRepository::currentCount).reversed());
        return out.size() > 10 ? out.subList(0, 10) : out;
    }

    // ActivityStore

    @Override
    public synchronized List<MinuteCounts> minuteCounts(Instant from, Instant to) {
        List<MinuteCounts> out = new ArrayList<>();
        minutes.subMap(from.getEpochSecond() / 60, true, (to.getEpochSecond() - 1) / 60, true)
                .forEach((m, b) -> out.add(new MinuteCounts(Instant.ofEpochSecond(m * 60), b.counts)));
        return out;
    }

    @Override
    public synchronized long activeRepositories(Instant from, Instant to) {
        return distinctRepos(from.getEpochSecond() / 60, (to.getEpochSecond() - 1) / 60);
    }

    @Override
    public synchronized List<Long> activeRepositorySeries(Instant from, Instant to, int bucketMinutes) {
        long first = Math.floorDiv(from.getEpochSecond() / 60, (long) bucketMinutes) * bucketMinutes;
        long last = (to.getEpochSecond() - 1) / 60;
        List<Long> out = new ArrayList<>();
        for (long b = first; b <= last; b += bucketMinutes) {
            out.add(distinctRepos(b, Math.min(last, b + bucketMinutes - 1)));
        }
        return out;
    }

    private long distinctRepos(long firstMinute, long lastMinute) {
        Set<String> repos = new HashSet<>();
        minutes.subMap(firstMinute, true, lastMinute, true).values().forEach(b -> repos.addAll(b.repos.keySet()));
        return repos.size();
    }

    @Override
    public synchronized Map<String, ActivityCounts> repositoryTotals(Instant from, Instant to, int limit) {
        Map<String, ActivityCounts> totals = new HashMap<>();
        minutes.subMap(from.getEpochSecond() / 60, true, (to.getEpochSecond() - 1) / 60, true)
                .values()
                .forEach(b -> b.repos.forEach((r, c) -> totals.merge(r, c, ActivityCounts::plus)));
        Map<String, ActivityCounts> top = new LinkedHashMap<>();
        totals.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, ActivityCounts> e) -> e.getValue().total())
                        .reversed())
                .limit(limit)
                .forEach(e -> top.put(e.getKey(), e.getValue()));
        return top;
    }

    @Override
    public synchronized Map<String, List<MinuteCounts>> repositoryMinutes(
            Collection<String> repos, Instant from, Instant to) {
        Map<String, List<MinuteCounts>> out = new HashMap<>();
        repos.forEach(r -> out.put(r, new ArrayList<>()));
        minutes.subMap(from.getEpochSecond() / 60, true, (to.getEpochSecond() - 1) / 60, true)
                .forEach((m, b) -> {
                    for (String r : repos) {
                        ActivityCounts c = b.repos.get(r);
                        if (c != null) {
                            out.get(r).add(new MinuteCounts(Instant.ofEpochSecond(m * 60), c));
                        }
                    }
                });
        return out;
    }

    public synchronized int minuteBucketCount() {
        return minutes.size();
    }

    static Duration sixHours() {
        return Duration.ofHours(6);
    }
}
