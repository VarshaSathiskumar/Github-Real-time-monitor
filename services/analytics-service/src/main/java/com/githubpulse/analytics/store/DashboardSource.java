package com.githubpulse.analytics.store;

import com.githubpulse.analytics.model.EventView;
import com.githubpulse.analytics.model.Mode;
import com.githubpulse.analytics.model.Range;
import com.githubpulse.analytics.model.RisingRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** One data universe (live or demo) as seen by the dashboard. */
public interface DashboardSource {

    Mode mode();

    Instant now();

    ActivityStore store(Range range);

    /** First minute for which any data exists; used to decide whether a previous-window comparison is valid. */
    Optional<Instant> observationStart();

    List<EventView> recentEvents(int limit);

    List<RisingRepository> rising();
}
