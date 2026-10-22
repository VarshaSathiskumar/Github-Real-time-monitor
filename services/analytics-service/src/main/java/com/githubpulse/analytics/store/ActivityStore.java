package com.githubpulse.analytics.store;

import com.githubpulse.analytics.model.ActivityCounts;
import com.githubpulse.analytics.model.MinuteCounts;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Read model over per-minute activity, implemented by Redis, PostgreSQL and the demo simulator. */
public interface ActivityStore {

    /** Non-empty minutes in [from, to), ascending. */
    List<MinuteCounts> minuteCounts(Instant from, Instant to);

    long activeRepositories(Instant from, Instant to);

    /** Distinct repositories per bucket of {@code bucketMinutes}, aligned to the epoch, dense and ascending. */
    List<Long> activeRepositorySeries(Instant from, Instant to, int bucketMinutes);

    /** Totals per repository in [from, to), at most {@code limit} repositories (the most active ones). */
    Map<String, ActivityCounts> repositoryTotals(Instant from, Instant to, int limit);

    /** Per-minute counts of the given repositories in [from, to). */
    Map<String, List<MinuteCounts>> repositoryMinutes(Collection<String> repos, Instant from, Instant to);
}
