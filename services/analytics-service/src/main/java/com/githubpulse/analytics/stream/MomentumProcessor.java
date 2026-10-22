package com.githubpulse.analytics.stream;

import com.githubpulse.analytics.config.PulseProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Consumes updates of the per-repository tumbling window counts, keeps a short window history per
 * repository and emits activity spikes via {@link MomentumRules}.
 */
public class MomentumProcessor implements Processor<Windowed<String>, Long, Void, Void> {

    private static final Logger log = LoggerFactory.getLogger(MomentumProcessor.class);
    private static final String FIRST_WINDOW_KEY = "first-window";

    /** Serialisable window history of one repository. */
    public static class RepoHistory {
        public Map<Long, Long> windows = new HashMap<>();
    }

    private final PulseProperties.Momentum cfg;
    private final Sinks.AggregateSink sink;
    private KeyValueStore<String, RepoHistory> history;
    private KeyValueStore<String, Long> meta;

    public MomentumProcessor(PulseProperties.Momentum cfg, Sinks.AggregateSink sink) {
        this.cfg = cfg;
        this.sink = sink;
    }

    @Override
    public void init(ProcessorContext<Void, Void> context) {
        this.history = context.getStateStore(PulseTopology.MOMENTUM_STORE);
        this.meta = context.getStateStore(PulseTopology.MOMENTUM_META);
        context.schedule(Duration.ofMinutes(30), PunctuationType.WALL_CLOCK_TIME, this::purge);
    }

    @Override
    public void process(Record<Windowed<String>, Long> record) {
        String repo = record.key().key();
        long windowStart = record.key().window().start();
        long count = record.value();

        Long first = meta.get(FIRST_WINDOW_KEY);
        if (first == null || windowStart < first) {
            first = windowStart;
            meta.put(FIRST_WINDOW_KEY, first);
        }

        RepoHistory h = history.get(repo);
        if (h == null) {
            h = new RepoHistory();
        }
        h.windows.put(windowStart, count);
        long keepFrom = windowStart - (cfg.baselineWindows() + 1L) * cfg.window().toMillis();
        h.windows.keySet().removeIf(w -> w < keepFrom);
        history.put(repo, h);

        try {
            MomentumRules.evaluate(cfg, repo, windowStart, count, h.windows, first, Instant.now())
                    .ifPresent(sink::spike);
        } catch (RuntimeException e) {
            // A failing sink must not take the stream thread down; the next window update re-emits the spike.
            log.warn("Could not publish spike for {}: {}", repo, e.toString());
        }
    }

    private void purge(long wallClockMs) {
        long cutoff = wallClockMs - Duration.ofHours(2).toMillis();
        int removed = 0;
        try (KeyValueIterator<String, RepoHistory> it = history.all()) {
            while (it.hasNext()) {
                KeyValue<String, RepoHistory> kv = it.next();
                long newest = kv.value.windows.keySet().stream().mapToLong(Long::longValue).max().orElse(0);
                if (newest < cutoff) {
                    history.delete(kv.key);
                    removed++;
                }
            }
        }
        if (removed > 0) {
            log.info("Purged {} stale repository histories", removed);
        }
    }
}
