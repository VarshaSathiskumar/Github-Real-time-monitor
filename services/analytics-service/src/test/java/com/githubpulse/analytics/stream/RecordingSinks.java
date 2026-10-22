package com.githubpulse.analytics.stream;

import com.githubpulse.analytics.model.ActivityCounts;
import com.githubpulse.analytics.model.ParsedEvent;
import com.githubpulse.analytics.model.RisingRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory sinks for topology tests. */
final class RecordingSinks {

    private RecordingSinks() {}

    static class Events implements Sinks.EventSink {
        final List<ParsedEvent> persisted = Collections.synchronizedList(new ArrayList<>());
        final Set<Long> alreadyInDatabase = new HashSet<>();
        final AtomicInteger failuresLeft = new AtomicInteger();
        final AtomicInteger attempts = new AtomicInteger();

        @Override
        public Sinks.PersistResult persist(ParsedEvent event) {
            attempts.incrementAndGet();
            if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new IllegalStateException("database unavailable");
            }
            persisted.add(event);
            return alreadyInDatabase.contains(event.id()) ? Sinks.PersistResult.DUPLICATE : Sinks.PersistResult.NEW;
        }
    }

    static class Aggregates implements Sinks.AggregateSink {
        final Map<Instant, ActivityCounts> minutes = Collections.synchronizedMap(new HashMap<>());
        final Map<String, ActivityCounts> repoTotals = Collections.synchronizedMap(new HashMap<>());
        final List<RisingRepository> spikes = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void minute(Instant minute, ActivityCounts counts) {
            minutes.put(minute, counts);
        }

        @Override
        public void repoMinute(Instant minute, String repo, ActivityCounts counts) {
            repoTotals.put(repo + "@" + minute, counts);
        }

        @Override
        public void spike(RisingRepository spike) {
            spikes.add(spike);
        }

        ActivityCounts repoAt(String repo, Instant minute) {
            return repoTotals.get(repo + "@" + minute);
        }
    }

    static class Failures implements Sinks.FailureSink {
        final List<String> records = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void record(String topic, String key, String stage, String payload) {
            records.add(stage + ":" + key);
        }
    }
}
