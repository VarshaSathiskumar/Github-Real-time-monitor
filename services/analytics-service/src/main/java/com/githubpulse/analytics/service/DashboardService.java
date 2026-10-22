package com.githubpulse.analytics.service;

import com.githubpulse.analytics.api.Dtos.ActivityPoint;
import com.githubpulse.analytics.api.Dtos.ActivityResponse;
import com.githubpulse.analytics.api.Dtos.BreakdownItem;
import com.githubpulse.analytics.api.Dtos.BreakdownResponse;
import com.githubpulse.analytics.api.Dtos.EventsResponse;
import com.githubpulse.analytics.api.Dtos.Kpi;
import com.githubpulse.analytics.api.Dtos.MetricsResponse;
import com.githubpulse.analytics.api.Dtos.RisingResponse;
import com.githubpulse.analytics.api.Dtos.ScoreWeights;
import com.githubpulse.analytics.api.Dtos.TrendingRepo;
import com.githubpulse.analytics.api.Dtos.TrendingResponse;
import com.githubpulse.analytics.config.PulseProperties;
import com.githubpulse.analytics.demo.DemoDataStore;
import com.githubpulse.analytics.model.ActivityCounts;
import com.githubpulse.analytics.model.EventKind;
import com.githubpulse.analytics.model.MinuteCounts;
import com.githubpulse.analytics.model.Mode;
import com.githubpulse.analytics.model.Range;
import com.githubpulse.analytics.service.Buckets.Bucket;
import com.githubpulse.analytics.store.ActivityStore;
import com.githubpulse.analytics.store.DashboardSource;
import com.githubpulse.analytics.store.LiveSource;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;
import org.springframework.stereotype.Service;

/** Builds every dashboard view model from a {@link DashboardSource}, for live or demo data alike. */
@Service
public class DashboardService {

    public static final String TRENDING_DISCLAIMER =
            "Most active repositories in the observed GitHub event sample, ranked by activity score. "
                    + "This is not a ranking of GitHub's most popular repositories.";
    public static final String RISING_DISCLAIMER =
            "Activity spikes in the observed event sample compared with each repository's recent baseline. "
                    + "They are not definitive global GitHub trends.";

    private static final int TRENDING_LIMIT = 5;
    private static final int TRENDING_SPARKLINE_BUCKETS = 12;

    private final PulseProperties props;
    private final LiveSource live;
    private final DemoDataStore demo;

    public DashboardService(PulseProperties props, LiveSource live, DemoDataStore demo) {
        this.props = props;
        this.live = live;
        this.demo = demo;
    }

    public DashboardSource source(Mode mode) {
        return mode == Mode.DEMO ? demo : live;
    }

    /** Shortest comparison span (minutes) for which a change percentage is considered meaningful. */
    static final int MIN_PARTIAL_SPAN_MINUTES = 3;

    public MetricsResponse metrics(Mode mode, Range range) {
        DashboardSource src = source(mode);
        ActivityStore store = src.store(range);
        Instant end = windowEnd(src);
        Instant start = end.minus(range.duration());

        List<MinuteCounts> current = store.minuteCounts(start, end);
        ActivityCounts cur = Buckets.sum(current);
        long activeCur = store.activeRepositories(start, end);

        Comparison cmp = comparison(src, range, end);
        ActivityCounts cmpCur = ActivityCounts.ZERO;
        ActivityCounts cmpPrev = ActivityCounts.ZERO;
        long activeCmpCur = 0;
        long activeCmpPrev = 0;
        if (cmp.available()) {
            Instant mid = cmp.end().minus(cmp.span());
            Instant from = mid.minus(cmp.span());
            cmpCur = Buckets.sum(store.minuteCounts(mid, cmp.end()));
            cmpPrev = Buckets.sum(store.minuteCounts(from, mid));
            activeCmpCur = store.activeRepositories(mid, cmp.end());
            activeCmpPrev = store.activeRepositories(from, mid);
        }

        int sb = range.sparklineBucketMinutes();
        // Sparklines stop at the last complete minute so the minute in progress does not draw a false drop.
        Instant sparkEnd = end.minus(1, ChronoUnit.MINUTES);
        List<Bucket> buckets = Buckets.aggregate(current, start, sparkEnd, sb);

        return new MetricsResponse(
                mode.code(),
                range.code(),
                start,
                end,
                kpi(cur.total(), cmpCur.total(), cmpPrev.total(), cmp, series(buckets, ActivityCounts::total)),
                kpi(activeCur, activeCmpCur, activeCmpPrev, cmp, store.activeRepositorySeries(start, sparkEnd, sb)),
                kpi(cur.push(), cmpCur.push(), cmpPrev.push(), cmp, series(buckets, ActivityCounts::push)),
                commitsKpi(cur, cmpCur, cmpPrev, cmp, series(buckets, ActivityCounts::commits)),
                kpi(cur.pullRequest(), cmpCur.pullRequest(), cmpPrev.pullRequest(), cmp, series(buckets, ActivityCounts::pullRequest)),
                kpi(cur.fork(), cmpCur.fork(), cmpPrev.fork(), cmp, series(buckets, ActivityCounts::fork)),
                Instant.now());
    }

    /**
     * Two adjacent, equally long spans ending at {@code end}. With a full history these are the selected
     * window and the previous equivalent window. While less history exists the spans shrink to half of
     * what has been observed (at least {@value #MIN_PARTIAL_SPAN_MINUTES} minutes each), and the basis text says so.
     */
    record Comparison(boolean available, Instant end, Duration span, String basis) {}

    static Comparison comparison(DashboardSource src, Range range, Instant end) {
        Instant obs = src.observationStart().map(o -> o.minus(1, ChronoUnit.MINUTES)).orElse(null);
        if (obs == null) {
            return new Comparison(false, end, Duration.ZERO, null);
        }
        Instant prevStart = end.minus(range.duration()).minus(range.duration());
        Instant from = obs.isAfter(prevStart) ? obs : prevStart;
        if (!from.isAfter(prevStart) || Duration.between(from, end).compareTo(range.duration().multipliedBy(2)) >= 0) {
            return new Comparison(true, end, range.duration(), "Compared with the previous " + range.code() + " window");
        }
        // Not enough history for two full windows: compare the latest complete minutes with the span before them.
        Instant cmpEnd = end.minus(1, ChronoUnit.MINUTES);
        long half = Duration.between(from, cmpEnd).toMinutes() / 2;
        if (half < MIN_PARTIAL_SPAN_MINUTES) {
            return new Comparison(false, cmpEnd, Duration.ZERO, null);
        }
        return new Comparison(
                true,
                cmpEnd,
                Duration.ofMinutes(half),
                "Only " + (half * 2) + " minutes of data observed so far: last " + half + " min compared with the " + half
                        + " min before");
    }

    public ActivityResponse activity(Mode mode, Range range) {
        DashboardSource src = source(mode);
        // The minute in progress is incomplete and would draw a misleading drop, so charts stop at the last full minute.
        Instant end = src.now().truncatedTo(ChronoUnit.MINUTES);
        Instant start = end.minus(range.duration());
        int b = range.bucketMinutes();
        Instant obs = src.observationStart().orElse(null);

        List<Bucket> buckets = Buckets.aggregate(src.store(range).minuteCounts(start, end), start, end, b);
        List<ActivityPoint> points = new ArrayList<>(buckets.size());
        for (Bucket bucket : buckets) {
            Instant bucketEnd = bucket.start().plus(b, ChronoUnit.MINUTES);
            // Do not draw a flat zero line for time before anything was observed.
            if (obs != null && bucketEnd.isBefore(obs)) {
                continue;
            }
            // Edge buckets cover fewer than b minutes of the window; average over the minutes actually covered.
            Instant from = bucket.start().isBefore(start) ? start : bucket.start();
            Instant to = bucketEnd.isAfter(end) ? end : bucketEnd;
            double minutes = Math.max(1, Duration.between(from, to).toMinutes());
            ActivityCounts c = bucket.counts();
            points.add(new ActivityPoint(
                    bucket.start(), round(c.total() / minutes), round(c.push() / minutes),
                    round(c.pullRequest() / minutes)));
        }
        if (obs == null) {
            points.clear();
        }
        return new ActivityResponse(mode.code(), range.code(), b, points, Instant.now());
    }

    public TrendingResponse trending(Mode mode, Range range) {
        DashboardSource src = source(mode);
        ActivityStore store = src.store(range);
        Instant end = windowEnd(src);
        Instant start = end.minus(range.duration());
        PulseProperties.Score w = props.score();

        Map<String, ActivityCounts> totals = store.repositoryTotals(start, end, 200);
        List<Map.Entry<String, ActivityCounts>> ranked = totals.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, ActivityCounts>>comparingDouble(e -> -e.getValue().score(w))
                        .thenComparingLong(e -> -e.getValue().total())
                        .thenComparing(Map.Entry::getKey))
                .limit(TRENDING_LIMIT)
                .toList();

        Map<String, List<MinuteCounts>> minutes =
                store.repositoryMinutes(ranked.stream().map(Map.Entry::getKey).toList(), start, end);
        int sb = Math.max(1, (int) Math.ceil(range.minutes() / (double) TRENDING_SPARKLINE_BUCKETS));
        double top = ranked.isEmpty() ? 1 : Math.max(1, ranked.get(0).getValue().score(w));

        List<TrendingRepo> items = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<String, ActivityCounts> e : ranked) {
            String name = e.getKey();
            ActivityCounts c = e.getValue();
            double score = c.score(w);
            int slash = name.indexOf('/');
            items.add(new TrendingRepo(
                    rank++,
                    name,
                    slash > 0 ? name.substring(0, slash) : name,
                    "https://github.com/" + name,
                    round(score),
                    round(score / top),
                    c.total(),
                    c.push(),
                    c.pullRequest(),
                    c.fork(),
                    c.otherForScore(),
                    series(Buckets.aggregate(minutes.getOrDefault(name, List.of()), start, end, sb), ActivityCounts::total)));
        }
        return new TrendingResponse(
                mode.code(),
                range.code(),
                "Activity Score = Push x %s + Pull Request x %s + Fork x %s + Other x %s"
                        .formatted(num(w.push()), num(w.pullRequest()), num(w.fork()), num(w.other())),
                new ScoreWeights(w.push(), w.pullRequest(), w.fork(), w.other()),
                TRENDING_DISCLAIMER,
                items,
                Instant.now());
    }

    public BreakdownResponse breakdown(Mode mode, Range range) {
        DashboardSource src = source(mode);
        Instant end = windowEnd(src);
        Instant start = end.minus(range.duration());
        ActivityCounts c = Buckets.sum(src.store(range).minuteCounts(start, end));

        List<BreakdownItem> items = new ArrayList<>();
        for (EventKind kind : EventKind.values()) {
            long n = c.countOf(kind);
            items.add(new BreakdownItem(kind, kind.label(), n, c.total() == 0 ? 0 : round(n * 100.0 / c.total())));
        }
        return new BreakdownResponse(mode.code(), range.code(), c.total(), items, Instant.now());
    }

    public RisingResponse rising(Mode mode) {
        PulseProperties.Momentum m = props.momentum();
        return new RisingResponse(
                mode.code(),
                (int) m.window().toMinutes(),
                m.baselineWindows(),
                m.minEvents(),
                RISING_DISCLAIMER,
                source(mode).rising(),
                Instant.now());
    }

    public EventsResponse recentEvents(Mode mode, int limit) {
        return new EventsResponse(mode.code(), source(mode).recentEvents(limit), Instant.now());
    }

    static Instant windowEnd(DashboardSource src) {
        return src.now().truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofMinutes(1));
    }

    private static Kpi kpi(long current, long cmpCurrent, long cmpPrevious, Comparison cmp, List<Long> sparkline) {
        Double change = null;
        if (cmp.available() && cmpPrevious > 0) {
            change = round((cmpCurrent - cmpPrevious) * 100.0 / cmpPrevious);
        }
        return new Kpi(current, change, sparkline, null, change == null ? null : cmp.basis());
    }

    static final String COMMITS_UNAVAILABLE =
            "Not available: GitHub's public events API no longer reports commit counts in push events.";

    /** Commit totals only exist for push events that reported a count; otherwise the KPI is unavailable. */
    static Kpi commitsKpi(
            ActivityCounts cur, ActivityCounts cmpCur, ActivityCounts cmpPrev, Comparison cmp, List<Long> sparkline) {
        if (cur.commitReports() == 0) {
            return new Kpi(null, null, List.of(), COMMITS_UNAVAILABLE, null);
        }
        Kpi k = kpi(cur.commits(), cmpCur.commits(), cmpPrev.commits(), cmp, sparkline);
        boolean comparable = cmpPrev.commitReports() > 0 && cmpCur.commitReports() > 0;
        String note = cur.commitReports() < cur.push()
                ? "Based on " + cur.commitReports() + " of " + cur.push() + " push events that reported a commit count."
                : null;
        return new Kpi(k.value(), comparable ? k.changePct() : null, k.sparkline(), note, comparable ? k.changeBasis() : null);
    }

    private static List<Long> series(List<Bucket> buckets, ToLongFunction<ActivityCounts> f) {
        return buckets.stream().map(b -> f.applyAsLong(b.counts())).toList();
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static String num(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
