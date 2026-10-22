package com.githubpulse.analytics.stream;

import com.githubpulse.analytics.model.ActivityCounts;
import com.githubpulse.analytics.model.ParsedEvent;
import com.githubpulse.analytics.model.RisingRepository;
import java.time.Instant;

/** Side-effect boundaries of the topology, kept as interfaces so the topology is testable without I/O. */
public final class Sinks {

    private Sinks() {}

    public enum PersistResult {
        NEW,
        DUPLICATE
    }

    /** Idempotent persistence of one event (PostgreSQL insert, Redis live structures). */
    public interface EventSink {
        PersistResult persist(ParsedEvent event);
    }

    /** Receives absolute (not incremental) window values, so repeated delivery is harmless. */
    public interface AggregateSink {
        void minute(Instant minute, ActivityCounts counts);

        void repoMinute(Instant minute, String repo, ActivityCounts counts);

        void spike(RisingRepository spike);
    }

    public interface FailureSink {
        void record(String topic, String key, String stage, String payload);
    }
}
