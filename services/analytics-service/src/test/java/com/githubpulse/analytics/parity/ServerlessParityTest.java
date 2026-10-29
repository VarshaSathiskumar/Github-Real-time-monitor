package com.githubpulse.analytics.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.githubpulse.analytics.TestProps;
import com.githubpulse.analytics.config.PulseProperties;
import com.githubpulse.analytics.demo.DemoDataStore;
import com.githubpulse.analytics.model.ActivityCounts;
import com.githubpulse.analytics.model.Mode;
import com.githubpulse.analytics.model.MinuteCounts;
import com.githubpulse.analytics.model.ParsedEvent;
import com.githubpulse.analytics.model.Range;
import com.githubpulse.analytics.service.DashboardService;
import com.githubpulse.analytics.store.ActivityStore;
import com.githubpulse.analytics.store.LiveSource;
import com.githubpulse.analytics.stream.EventParser;
import com.githubpulse.analytics.stream.MomentumRules;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Golden file tests shared with the TypeScript functions of the serverless-lite deployment
 * (frontend/api). The inputs live in {@code parity/*.json}; the expected outputs are produced by THIS Java
 * code and committed next to them. The TypeScript tests assert the same files, so a behavioural change on
 * either side fails a test until both agree again.
 *
 * <p>Regenerate the expected files after an intentional change with {@code PARITY_UPDATE=1 ./gradlew
 * :services:analytics-service:test --tests '*ServerlessParityTest'} and review the diff.
 */
class ServerlessParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private static final boolean UPDATE = "1".equals(System.getenv("PARITY_UPDATE"));

    @Test
    void eventParsingMatchesGolden() throws IOException {
        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode c : read("events.json")) {
            JsonNode raw = c.get("raw");
            String json = raw.isTextual() ? raw.asText() : MAPPER.writeValueAsString(raw);
            ObjectNode o = out.addObject();
            o.put("name", c.get("name").asText());
            try {
                ParsedEvent e = EventParser.parse(json);
                o.put("ok", true);
                o.put("id", e.id());
                o.put("type", e.type());
                o.put("kind", e.kind().name());
                o.put("actor", e.actor());
                o.put("repoId", e.repoId());
                o.put("repoName", e.repoName());
                o.put("repoUrl", e.repoUrl());
                if (e.commits() != null) {
                    o.put("commits", e.commits());
                }
                if (e.action() != null) {
                    o.put("action", e.action());
                }
                o.put("description", e.description());
                o.put("url", e.url());
                o.put("createdAt", e.createdAt().toString());
                o.put("ingestedAt", e.ingestedAt().toString());
                o.put("counts", ActivityCounts.of(e).toCompact());
            } catch (RuntimeException ex) {
                o.put("ok", false);
            }
        }
        check("parse.expected.json", out);
    }

    @Test
    void momentumRuleMatchesGolden() throws IOException {
        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode c : read("momentum.json").get("cases")) {
            JsonNode cfg = c.get("cfg");
            PulseProperties.Momentum momentum = new PulseProperties.Momentum(
                    Duration.ofMinutes(cfg.get("windowMinutes").asLong()),
                    cfg.get("baselineWindows").asInt(),
                    cfg.get("minHistoryWindows").asInt(),
                    cfg.get("minEvents").asLong(),
                    cfg.get("spikeRatio").asDouble(),
                    cfg.get("minBaseline").asDouble(),
                    Duration.ofMinutes(15));
            Map<Long, Long> history = new HashMap<>();
            c.get("history").fields().forEachRemaining(e -> history.put(Long.parseLong(e.getKey()), e.getValue().asLong()));

            var spike = MomentumRules.evaluate(
                    momentum,
                    c.get("repo").asText(),
                    c.get("windowStart").asLong(),
                    c.get("currentCount").asLong(),
                    history,
                    c.get("firstWindowStart").asLong(),
                    Instant.ofEpochMilli(c.get("detectedAt").asLong()));

            ObjectNode o = out.addObject();
            o.put("name", c.get("name").asText());
            spike.ifPresent(s -> {
                ObjectNode n = o.putObject("spike");
                n.put("repo", s.repo());
                n.put("repoUrl", s.repoUrl());
                n.put("windowStart", s.windowStart().toEpochMilli());
                n.put("windowMinutes", s.windowMinutes());
                n.put("currentCount", s.currentCount());
                n.put("baseline", s.baseline());
                if (s.pctIncrease() != null) {
                    n.put("pctIncrease", s.pctIncrease());
                }
                n.put("level", s.level());
                n.put("note", s.note());
                n.put("detectedAt", s.detectedAt().toEpochMilli());
            });
        }
        check("momentum.expected.json", out);
    }

    @Test
    void dashboardViewsMatchGolden() throws IOException {
        PulseProperties props = TestProps.create();
        ObjectNode out = MAPPER.createObjectNode();
        for (JsonNode scenario : read("views.json").get("scenarios")) {
            FixtureStore store = new FixtureStore();
            for (JsonNode e : scenario.get("events")) {
                store.add(e.get(0).asLong(), e.get(1).asText(), e.get(2).asText(), e.get(3).isNull() ? null : e.get(3).asInt());
            }
            Instant now = Instant.ofEpochMilli(scenario.get("now").asLong());
            LiveSource live = mock(LiveSource.class);
            when(live.now()).thenReturn(now);
            when(live.store(any(Range.class))).thenReturn(store);
            when(live.observationStart()).thenReturn(store.firstMinute());
            DashboardService service = new DashboardService(props, live, new DemoDataStore(props));

            ObjectNode byRange = out.putObject(scenario.get("name").asText());
            for (Range range : Range.values()) {
                ObjectNode views = byRange.putObject(range.code());
                views.set("metrics", MAPPER.valueToTree(service.metrics(Mode.LIVE, range)));
                views.set("activity", MAPPER.valueToTree(service.activity(Mode.LIVE, range)));
                views.set("trending", MAPPER.valueToTree(service.trending(Mode.LIVE, range)));
                views.set("breakdown", MAPPER.valueToTree(service.breakdown(Mode.LIVE, range)));
            }
        }
        check("views.expected.json", stripGeneratedAt(out));
    }

    // helpers

    private static Path dir() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("parity"))) {
            p = p.getParent();
        }
        assertThat(p).as("repository root containing parity/").isNotNull();
        return p.resolve("parity");
    }

    private static JsonNode read(String file) throws IOException {
        return MAPPER.readTree(Files.readString(dir().resolve(file)));
    }

    private static void check(String file, JsonNode actual) throws IOException {
        JsonNode normalised = MAPPER.readTree(MAPPER.writeValueAsString(actual));
        Path expectedFile = dir().resolve(file);
        if (UPDATE) {
            Files.writeString(expectedFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(normalised) + "\n");
            return;
        }
        assertThat(Files.exists(expectedFile)).as(file + " exists, run with PARITY_UPDATE=1 to create it").isTrue();
        assertThat(normalised).isEqualTo(MAPPER.readTree(Files.readString(expectedFile)));
    }

    private static JsonNode stripGeneratedAt(JsonNode node) {
        if (node instanceof ObjectNode o) {
            o.remove("generatedAt");
            o.elements().forEachRemaining(ServerlessParityTest::stripGeneratedAt);
        } else if (node instanceof ArrayNode a) {
            a.elements().forEachRemaining(ServerlessParityTest::stripGeneratedAt);
        }
        return node;
    }

    /** Minute buckets built straight from fixture events, behaving like PostgresActivityStore, the live data path. */
    private static final class FixtureStore implements ActivityStore {

        private final TreeMap<Long, ActivityCounts> minutes = new TreeMap<>();
        private final TreeMap<Long, Map<String, ActivityCounts>> repoMinutes = new TreeMap<>();

        void add(long at, String kind, String repo, Integer commits) {
            boolean push = kind.equals("PUSH");
            ActivityCounts one = new ActivityCounts(
                    1,
                    push ? 1 : 0,
                    kind.equals("PULL_REQUEST") ? 1 : 0,
                    kind.equals("FORK") ? 1 : 0,
                    kind.equals("WATCH") ? 1 : 0,
                    kind.equals("ISSUES") ? 1 : 0,
                    kind.equals("ISSUE_COMMENT") ? 1 : 0,
                    kind.equals("OTHER") ? 1 : 0,
                    push && commits != null ? commits : 0,
                    push && commits != null ? 1 : 0);
            long minute = at / 60_000;
            minutes.merge(minute, one, ActivityCounts::plus);
            repoMinutes.computeIfAbsent(minute, k -> new HashMap<>()).merge(repo, one, ActivityCounts::plus);
        }

        Optional<Instant> firstMinute() {
            return minutes.isEmpty() ? Optional.empty() : Optional.of(Instant.ofEpochSecond(minutes.firstKey() * 60));
        }

        private long first(Instant from) {
            return from.getEpochSecond() / 60;
        }

        private long last(Instant to) {
            return (to.getEpochSecond() - 1) / 60;
        }

        @Override
        public List<MinuteCounts> minuteCounts(Instant from, Instant to) {
            List<MinuteCounts> out = new ArrayList<>();
            minutes.subMap(first(from), true, last(to), true)
                    .forEach((m, c) -> out.add(new MinuteCounts(Instant.ofEpochSecond(m * 60), c)));
            return out;
        }

        @Override
        public long activeRepositories(Instant from, Instant to) {
            return distinct(first(from), last(to));
        }

        private long distinct(long a, long z) {
            Set<String> repos = new HashSet<>();
            repoMinutes.subMap(a, true, z, true).values().forEach(r -> repos.addAll(r.keySet()));
            return repos.size();
        }

        @Override
        public List<Long> activeRepositorySeries(Instant from, Instant to, int bucketMinutes) {
            long first = Math.floorDiv(first(from), (long) bucketMinutes) * bucketMinutes;
            long last = last(to);
            List<Long> out = new ArrayList<>();
            for (long b = first; b <= last; b += bucketMinutes) {
                // Like PostgresActivityStore (the production live path): the first bucket only counts minutes inside the window.
                out.add(distinct(Math.max(b, first(from)), Math.min(last, b + bucketMinutes - 1)));
            }
            return out;
        }

        @Override
        public Map<String, ActivityCounts> repositoryTotals(Instant from, Instant to, int limit) {
            Map<String, ActivityCounts> totals = new HashMap<>();
            repoMinutes.subMap(first(from), true, last(to), true)
                    .values()
                    .forEach(r -> r.forEach((k, c) -> totals.merge(k, c, ActivityCounts::plus)));
            Map<String, ActivityCounts> top = new LinkedHashMap<>();
            totals.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, ActivityCounts>>comparingLong(e -> -e.getValue().total())
                            .thenComparing(Map.Entry::getKey))
                    .limit(limit)
                    .forEach(e -> top.put(e.getKey(), e.getValue()));
            return top;
        }

        @Override
        public Map<String, List<MinuteCounts>> repositoryMinutes(Collection<String> repos, Instant from, Instant to) {
            Map<String, List<MinuteCounts>> out = new HashMap<>();
            repos.forEach(r -> out.put(r, new ArrayList<>()));
            repoMinutes.subMap(first(from), true, last(to), true).forEach((m, byRepo) -> {
                for (String r : repos) {
                    ActivityCounts c = byRepo.get(r);
                    if (c != null) {
                        out.get(r).add(new MinuteCounts(Instant.ofEpochSecond(m * 60), c));
                    }
                }
            });
            return out;
        }
    }
}
