package com.githubpulse.analytics.loadtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Measures the running pipeline. Start Kafka, Redis, PostgreSQL and the analytics service first, and
 * stop the ingestion service so real GitHub traffic does not pollute the counts.
 *
 * <pre>
 * ./gradlew :services:analytics-service:loadTest -Pargs="--events 20000"
 * </pre>
 *
 * Phases: (A) low rate, every event is a latency marker, observed over REST and WebSocket;
 * (B) throughput burst until the dashboard counters caught up; (C) API latency percentiles.
 */
public class LoadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final String kafka;
    private final String api;
    private final String topic;
    private final int burstEvents;
    private final int latencyEvents;
    private final AtomicLong idSeq = new AtomicLong(9_000_000_000_000L + (System.currentTimeMillis() % 1_000_000_000L) * 1000);

    LoadTest(Map<String, String> args) {
        this.kafka = args.getOrDefault("kafka", "localhost:9092");
        this.api = args.getOrDefault("api", "http://localhost:8080");
        this.topic = args.getOrDefault("topic", "github.events.raw");
        this.burstEvents = Integer.parseInt(args.getOrDefault("events", "20000"));
        this.latencyEvents = Integer.parseInt(args.getOrDefault("latency-events", "300"));
    }

    public static void main(String[] argv) throws Exception {
        Map<String, String> args = new java.util.HashMap<>();
        for (int i = 0; i + 1 < argv.length; i += 2) {
            args.put(argv[i].replaceFirst("^--", ""), argv[i + 1]);
        }
        new LoadTest(args).run(args.getOrDefault("out", "scripts/results/loadtest.json"));
    }

    void run(String out) throws Exception {
        Properties pp = new Properties();
        pp.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka);
        pp.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        pp.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        pp.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        pp.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        Map<String, Object> report = new java.util.LinkedHashMap<>();
        report.put("startedAt", Instant.now().toString());
        report.put("jvm", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        report.put("cpus", Runtime.getRuntime().availableProcessors());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(pp)) {
            report.put("latency", phaseLatency(producer));
            report.put("throughput", phaseThroughput(producer));
        }
        report.put("apiLatencyMs", phaseApi());

        Path path = Path.of(out);
        Files.createDirectories(path.getParent());
        Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("Report written to " + path);
    }

    // ---------------------------------------------------------------- phase A

    private Map<String, Object> phaseLatency(KafkaProducer<String, String> producer) throws Exception {
        Map<String, Long> sentAt = new ConcurrentHashMap<>();
        Map<String, Long> seenRest = new ConcurrentHashMap<>();
        Map<String, Long> seenWs = new ConcurrentHashMap<>();
        List<Long> kpiGaps = Collections.synchronizedList(new ArrayList<>());
        AtomicLong lastKpi = new AtomicLong();

        WebSocket ws = HTTP.newWebSocketBuilder()
                .buildAsync(URI.create(api.replaceFirst("^http", "ws") + "/ws"), new WebSocket.Listener() {
                    private final StringBuilder buf = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                        buf.append(data);
                        if (last) {
                            handle(buf.toString());
                            buf.setLength(0);
                        }
                        w.request(1);
                        return null;
                    }

                    void handle(String json) {
                        long now = System.nanoTime();
                        try {
                            JsonNode m = MAPPER.readTree(json);
                            String type = m.path("type").asText();
                            if ("kpi".equals(type)) {
                                long prev = lastKpi.getAndSet(now);
                                if (prev != 0) {
                                    kpiGaps.add((now - prev) / 1_000_000);
                                }
                            } else if ("events".equals(type)) {
                                for (JsonNode e : m.path("data")) {
                                    seenWs.putIfAbsent(e.path("id").asText(), now);
                                }
                            }
                        } catch (Exception ignored) {
                            // not JSON, ignore
                        }
                    }
                })
                .join();
        ws.sendText("{\"action\":\"subscribe\",\"mode\":\"live\",\"range\":\"15m\"}", true).join();

        AtomicBoolean running = new AtomicBoolean(true);
        Thread restPoller = new Thread(() -> {
            while (running.get()) {
                try {
                    JsonNode ev = get("/api/events/recent?limit=100").path("events");
                    long now = System.nanoTime();
                    for (JsonNode e : ev) {
                        seenRest.putIfAbsent(e.path("id").asText(), now);
                    }
                    Thread.sleep(25);
                } catch (Exception ignored) {
                    // transient
                }
            }
        });
        restPoller.setDaemon(true);
        restPoller.start();
        Thread.sleep(1500);

        for (int i = 0; i < latencyEvents; i++) {
            long id = idSeq.incrementAndGet();
            sentAt.put(Long.toString(id), System.nanoTime());
            producer.send(new ProducerRecord<>(topic, Long.toString(id), eventJson(id, "latency/repo" + (i % 7))));
            producer.flush();
            Thread.sleep(50);
        }
        Thread.sleep(7000);
        running.set(false);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("markerEvents", latencyEvents);
        out.put("sendToRestVisibleMs", percentiles(deltas(sentAt, seenRest)));
        out.put("sendToWebSocketMs", percentiles(deltas(sentAt, seenWs)));
        out.put("webSocketBroadcastIntervalMs", percentiles(new ArrayList<>(kpiGaps)));
        out.put("note", "sendToRestVisibleMs: time until GET /api/events/recent shows the event (Kafka, Streams, PostgreSQL, Redis). "
                + "sendToWebSocketMs: time until a pushed events message contains it (adds the 2-5 s batching interval).");
        return out;
    }

    // ---------------------------------------------------------------- phase B

    private Map<String, Object> phaseThroughput(KafkaProducer<String, String> producer) throws Exception {
        long before = metricTotal();
        List<Long> lagSamples = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean running = new AtomicBoolean(true);
        Thread lag = new Thread(() -> {
            while (running.get()) {
                try {
                    JsonNode lagNode = get("/api/system/status").path("analytics").path("consumerLag");
                    if (lagNode.isNumber()) {
                        lagSamples.add(lagNode.asLong());
                    }
                    Thread.sleep(1000);
                } catch (Exception ignored) {
                    // transient
                }
            }
        });
        lag.setDaemon(true);
        lag.start();

        long start = System.nanoTime();
        for (int i = 0; i < burstEvents; i++) {
            long id = idSeq.incrementAndGet();
            producer.send(new ProducerRecord<>(topic, Long.toString(id), eventJson(id, "burst/repo" + (i % 200))));
        }
        producer.flush();
        double produceSeconds = (System.nanoTime() - start) / 1e9;

        long deadline = System.currentTimeMillis() + Duration.ofMinutes(5).toMillis();
        long seen = 0;
        while (System.currentTimeMillis() < deadline) {
            seen = metricTotal() - before;
            if (seen >= burstEvents) {
                break;
            }
            Thread.sleep(200);
        }
        double totalSeconds = (System.nanoTime() - start) / 1e9;
        running.set(false);

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("eventsSent", burstEvents);
        out.put("eventsReflectedInDashboard", seen);
        out.put("produceSeconds", round(produceSeconds));
        out.put("producerEventsPerSecond", round(burstEvents / produceSeconds));
        out.put("endToEndSeconds", round(totalSeconds));
        out.put("endToEndEventsPerSecond", round(seen / totalSeconds));
        out.put("consumerLagMax", lagSamples.stream().mapToLong(Long::longValue).max().orElse(-1));
        out.put("consumerLagSamples", lagSamples.size());
        out.put("note", "endToEnd measures time until GET /api/metrics counted every event, so it includes Kafka, "
                + "dedup, one PostgreSQL insert per event, windowed aggregation and the Redis write.");
        return out;
    }

    // ---------------------------------------------------------------- phase C

    private Map<String, Object> phaseApi() throws Exception {
        String[] paths = {
            "/api/metrics?range=1h", "/api/activity?range=1h", "/api/activity?range=24h",
            "/api/repositories/trending?range=1h", "/api/events/recent", "/api/activity/breakdown?range=1h",
            "/api/repositories/rising", "/api/system/status"
        };
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (String path : paths) {
            get(path); // warm up
            List<Long> ms = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                long t = System.nanoTime();
                get(path);
                ms.add((System.nanoTime() - t) / 1_000_000);
            }
            out.put(path, percentiles(ms));
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers

    private long metricTotal() throws Exception {
        return get("/api/metrics?range=1h").path("totalEvents").path("value").asLong();
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> res = HTTP.send(
                HttpRequest.newBuilder(URI.create(api + path)).timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
        return MAPPER.readTree(res.body());
    }

    private static String eventJson(long id, String repo) {
        String now = Instant.now().toString();
        return "{\"id\":\"" + id + "\",\"type\":\"PushEvent\",\"actor\":{\"login\":\"load" + (id % 500)
                + "\"},\"repo\":{\"id\":" + Math.abs(repo.hashCode()) + ",\"name\":\"" + repo
                + "\"},\"payload\":{\"ref\":\"refs/heads/main\"},\"created_at\":\"" + now + "\",\"ingested_at\":\"" + now + "\"}";
    }

    private static List<Long> deltas(Map<String, Long> sent, Map<String, Long> seen) {
        List<Long> out = new ArrayList<>();
        sent.forEach((id, t0) -> {
            Long t1 = seen.get(id);
            if (t1 != null) {
                out.add((t1 - t0) / 1_000_000);
            }
        });
        return out;
    }

    private static Map<String, Object> percentiles(List<Long> values) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("samples", values.size());
        if (values.isEmpty()) {
            return out;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        out.put("p50", sorted.get((int) Math.min(sorted.size() - 1, Math.round(sorted.size() * 0.50))));
        out.put("p95", sorted.get((int) Math.min(sorted.size() - 1, Math.round(sorted.size() * 0.95))));
        out.put("p99", sorted.get((int) Math.min(sorted.size() - 1, Math.round(sorted.size() * 0.99))));
        out.put("max", sorted.get(sorted.size() - 1));
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
