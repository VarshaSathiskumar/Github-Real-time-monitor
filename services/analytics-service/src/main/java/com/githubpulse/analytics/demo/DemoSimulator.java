package com.githubpulse.analytics.demo;

import com.githubpulse.analytics.config.PulseProperties;
import com.githubpulse.analytics.model.EventKind;
import com.githubpulse.analytics.model.EventView;
import com.githubpulse.analytics.model.Mode;
import com.githubpulse.analytics.store.EventFeed;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Generates a realistic simulated GitHub event workload (pushes, pull requests, forks, stars,
 * issues, periodic spikes on a random repository). Runs only while somebody is using demo mode and
 * never touches Kafka, Redis or PostgreSQL.
 */
@Component
public class DemoSimulator {

    private static final Logger log = LoggerFactory.getLogger(DemoSimulator.class);

    private static final List<String> REPOS = List.of(
            "facebook/react", "microsoft/vscode", "vercel/next.js", "spring-projects/spring-boot",
            "torvalds/linux", "tensorflow/tensorflow", "kubernetes/kubernetes", "golang/go", "rust-lang/rust",
            "python/cpython", "apache/kafka", "apache/spark", "redis/redis", "hashicorp/terraform",
            "grafana/grafana", "flutter/flutter", "vuejs/core", "angular/angular", "nodejs/node", "denoland/deno",
            "django/django", "pallets/flask", "dotnet/runtime", "elastic/elasticsearch", "prometheus/prometheus",
            "home-assistant/core", "ollama/ollama", "langchain-ai/langchain", "huggingface/transformers",
            "pytorch/pytorch", "sveltejs/svelte", "tailwindlabs/tailwindcss", "shadcn-ui/ui", "supabase/supabase",
            "ansible/ansible", "godotengine/godot", "neovim/neovim", "rails/rails", "laravel/framework",
            "JetBrains/kotlin");

    private static final List<EventKind> KINDS = List.of(
            EventKind.PUSH, EventKind.WATCH, EventKind.PULL_REQUEST, EventKind.ISSUE_COMMENT, EventKind.ISSUES,
            EventKind.FORK, EventKind.OTHER);
    private static final double[] KIND_WEIGHTS = {0.46, 0.20, 0.10, 0.08, 0.05, 0.04, 0.07};

    private static final List<String> ADJECTIVES =
            List.of("swift", "quiet", "brave", "lucky", "clever", "rapid", "sunny", "mellow", "bold", "keen");
    private static final List<String> NOUNS =
            List.of("fox", "coder", "hacker", "otter", "gopher", "ferris", "panda", "falcon", "maker", "wizard");

    private static final double TICK_SECONDS = 0.5;

    private final PulseProperties props;
    private final DemoDataStore store;
    private final EventFeed feed;
    private final Random random = new Random();
    private final double[] repoWeights;
    private final double weightSum;
    private final AtomicLong sequence = new AtomicLong();
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "demo-simulator"));

    private volatile ScheduledFuture<?> task;
    private volatile Instant startedAt;
    private volatile long leaseUntilMillis;
    private String spikeRepo;
    private long spikeEndsAt;
    private long nextSpikeAt;

    public DemoSimulator(PulseProperties props, DemoDataStore store, EventFeed feed) {
        this.props = props;
        this.store = store;
        this.feed = feed;
        this.repoWeights = new double[REPOS.size()];
        double sum = 0;
        for (int i = 0; i < repoWeights.length; i++) {
            repoWeights[i] = 1.0 / Math.pow(i + 1, 0.9);
            sum += repoWeights[i];
        }
        this.weightSum = sum;
    }

    /** Starts the simulator if needed and extends its lease; call regularly while demo mode is in use. */
    public synchronized void ensureRunning() {
        touch();
        if (task != null) {
            return;
        }
        log.info("Starting demo simulator");
        store.clear();
        feed.clear(Mode.DEMO);
        backfill();
        startedAt = Instant.now();
        nextSpikeAt = System.currentTimeMillis() + 20_000;
        task = executor.scheduleAtFixedRate(this::tickSafely, 0, (long) (TICK_SECONDS * 1000), TimeUnit.MILLISECONDS);
    }

    public void touch() {
        leaseUntilMillis = System.currentTimeMillis() + props.demo().idleTimeout().toMillis();
    }

    public synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
            log.info("Demo simulator stopped");
        }
        store.clear();
        feed.clear(Mode.DEMO);
        startedAt = null;
    }

    public boolean isRunning() {
        return task != null;
    }

    public Instant startedAt() {
        return startedAt;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private void tickSafely() {
        try {
            if (System.currentTimeMillis() > leaseUntilMillis) {
                stop();
                return;
            }
            tick();
        } catch (RuntimeException e) {
            log.warn("Demo tick failed: {}", e.toString());
        }
    }

    private void tick() {
        long nowMs = System.currentTimeMillis();
        Instant now = Instant.ofEpochMilli(nowMs);

        manageSpike(nowMs);
        int n = poisson(props.demo().eventsPerSecond() * TICK_SECONDS * loadFactor(now.getEpochSecond() / 60));
        for (int i = 0; i < n; i++) {
            emit(now, pickRepo(), true);
        }
        if (spikeRepo != null) {
            int extra = poisson(1.6 * TICK_SECONDS);
            for (int i = 0; i < extra; i++) {
                emit(now, spikeRepo, true);
            }
        }
        store.pruneOlderThan(now.minus(Duration.ofHours(7)));
    }

    private void manageSpike(long nowMs) {
        if (spikeRepo != null && nowMs > spikeEndsAt) {
            spikeRepo = null;
            nextSpikeAt = nowMs + 60_000 + random.nextInt(60_000);
        } else if (spikeRepo == null && nowMs > nextSpikeAt) {
            // Pick from the quietest repositories so the baseline is small and the spike is obvious.
            spikeRepo = REPOS.get(REPOS.size() - 1 - random.nextInt(REPOS.size() / 3));
            spikeEndsAt = nowMs + 100_000;
            log.info("Demo spike started on {}", spikeRepo);
        }
    }

    private void backfill() {
        long nowMinute = Instant.now().getEpochSecond() / 60;
        long minutes = props.demo().backfill().toMinutes();
        for (long m = nowMinute - minutes; m < nowMinute; m++) {
            double lambda = props.demo().eventsPerSecond() * 60 * loadFactor(m);
            int n = (int) Math.max(0, Math.round(lambda + Math.sqrt(lambda) * random.nextGaussian()));
            Instant minuteStart = Instant.ofEpochSecond(m * 60);
            boolean tail = m >= nowMinute - 3;
            for (int i = 0; i < n; i++) {
                emit(minuteStart.plusMillis(random.nextInt(60_000)), pickRepo(), tail);
            }
        }
    }

    private double loadFactor(long epochMinute) {
        return 0.85 + 0.25 * Math.sin(epochMinute / 37.0) + 0.1 * Math.sin(epochMinute / 9.0);
    }

    private void emit(Instant at, String repo, boolean publish) {
        EventKind kind = pickKind();
        int commits = kind == EventKind.PUSH ? 1 + random.nextInt(5) : 0;
        store.add(at, kind, repo, commits);
        if (publish) {
            String actor = ADJECTIVES.get(random.nextInt(ADJECTIVES.size())) + "-"
                    + NOUNS.get(random.nextInt(NOUNS.size())) + random.nextInt(100);
            EventView view = new EventView(
                    "demo-" + sequence.incrementAndGet(),
                    kind,
                    kind.name(),
                    actor,
                    repo,
                    "https://github.com/" + repo,
                    describe(kind, actor, repo, commits),
                    "https://github.com/" + repo,
                    at,
                    at,
                    true);
            store.addRecent(view);
            if (Duration.between(at, Instant.now()).abs().getSeconds() < 5) {
                feed.offer(Mode.DEMO, view);
            }
        }
    }

    static String describe(EventKind kind, String actor, String repo, int commits) {
        return switch (kind) {
            case PUSH -> actor + " pushed " + commits + (commits == 1 ? " commit" : " commits") + " to " + repo;
            case PULL_REQUEST -> actor + " opened a pull request in " + repo;
            case FORK -> actor + " forked " + repo;
            case WATCH -> actor + " starred " + repo;
            case ISSUES -> actor + " opened an issue in " + repo;
            case ISSUE_COMMENT -> actor + " commented on an issue in " + repo;
            case OTHER -> actor + " triggered create activity in " + repo;
        };
    }

    private EventKind pickKind() {
        double r = random.nextDouble();
        double acc = 0;
        for (int i = 0; i < KIND_WEIGHTS.length; i++) {
            acc += KIND_WEIGHTS[i];
            if (r < acc) {
                return KINDS.get(i);
            }
        }
        return EventKind.PUSH;
    }

    private String pickRepo() {
        double r = random.nextDouble() * weightSum;
        for (int i = 0; i < repoWeights.length; i++) {
            r -= repoWeights[i];
            if (r <= 0) {
                return REPOS.get(i);
            }
        }
        return REPOS.get(0);
    }

    private int poisson(double lambda) {
        double l = Math.exp(-lambda);
        int k = 0;
        double p = 1;
        do {
            k++;
            p *= random.nextDouble();
        } while (p > l);
        return k - 1;
    }
}
