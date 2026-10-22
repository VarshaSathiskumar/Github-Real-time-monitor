package com.githubpulse.analytics.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.githubpulse.analytics.config.PulseProperties;
import com.githubpulse.analytics.model.ActivityCounts;
import com.githubpulse.analytics.model.ParsedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Branched;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KGroupedStream;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.processor.api.ProcessorSupplier;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.streams.state.WindowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Kafka Streams topology:
 *
 * <pre>
 * raw ──parse──┬─ malformed ──────────────────────────────► DLQ
 *              └─ dedup (event id, state store)
 *                    └─ persist (retry) ─┬─ failed ───────► DLQ
 *                                        └─ new events
 *                                             ├─ 1 min window, global   ► minute aggregates
 *                                             ├─ 1 min window, per repo ► repository aggregates
 *                                             └─ 5 min window, per repo ► momentum / spike detector
 * </pre>
 *
 * <p>Time semantics: windows use the Kafka record timestamp, which is the moment the ingestion
 * service published the event, not GitHub's {@code created_at}. GitHub's events feed lags the real
 * world by seconds to minutes, so {@code created_at} would make events arrive "late" by design.
 * Using observation time keeps windows monotonic; records that still arrive later than the grace
 * period are dropped by Kafka Streams and counted in its {@code dropped-records} metric.
 */
public class PulseTopology {

    public static final String DEDUP_STORE = "dedup-store";
    public static final String MOMENTUM_STORE = "momentum-store";
    public static final String MOMENTUM_META = "momentum-meta";

    private static final Logger log = LoggerFactory.getLogger(PulseTopology.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final PulseProperties props;
    private final Sinks.EventSink eventSink;
    private final Sinks.AggregateSink aggregateSink;
    private final Sinks.FailureSink failureSink;

    public PulseTopology(
            PulseProperties props,
            Sinks.EventSink eventSink,
            Sinks.AggregateSink aggregateSink,
            Sinks.FailureSink failureSink) {
        this.props = props;
        this.eventSink = eventSink;
        this.aggregateSink = aggregateSink;
        this.failureSink = failureSink;
    }

    record ParseResult(ParsedEvent event, String error, String raw) {}

    record Outcome(ParsedEvent event, Sinks.PersistResult result, String error) {}

    public void build(StreamsBuilder builder) {
        Serde<String> strings = Serdes.String();
        Serde<ActivityCounts> countsSerde = new JsonSerde<>(ActivityCounts.class);
        Duration grace = props.streams().gracePeriod();

        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(DEDUP_STORE), strings, Serdes.Long()));
        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(MOMENTUM_STORE),
                strings,
                new JsonSerde<>(MomentumProcessor.RepoHistory.class)));
        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(MOMENTUM_META), strings, Serdes.Long()));

        KStream<String, String> raw =
                builder.stream(props.topics().raw(), Consumed.with(strings, strings));

        // 1. Parse and classify. Malformed events are diverted to the DLQ and never crash the stream.
        Map<String, KStream<String, ParseResult>> parsed = raw.mapValues(PulseTopology::tryParse)
                .split(Named.as("parse-"))
                .branch((k, r) -> r.event() != null, Branched.as("ok"))
                .defaultBranch(Branched.as("bad"));

        sendToDlq(parsed.get("parse-bad").mapValues((k, r) -> dlqRecord("parse", k, r.error(), r.raw())), "parse");

        // 2. Deduplicate by GitHub event ID.
        ProcessorSupplier<String, ParsedEvent, String, ParsedEvent> dedup =
                () -> new DedupProcessor(props.streams().dedupTtl());
        KStream<String, ParsedEvent> unique =
                parsed.get("parse-ok").mapValues(ParseResult::event).process(dedup, DEDUP_STORE);

        // 3. Persist with retries; permanent failures go to the DLQ.
        Map<String, KStream<String, Outcome>> persisted = unique.mapValues(this::persistWithRetry)
                .split(Named.as("persist-"))
                .branch((k, o) -> o.error() == null, Branched.as("ok"))
                .defaultBranch(Branched.as("failed"));

        sendToDlq(
                persisted.get("persist-failed").mapValues((k, o) -> dlqRecord("persist", k, o.error(), toJson(o.event()))),
                "persist");

        // Only events that were new to PostgreSQL feed the aggregations, so duplicates never inflate analytics.
        KStream<String, ParsedEvent> fresh = persisted
                .get("persist-ok")
                .filter((k, o) -> o.result() == Sinks.PersistResult.NEW)
                .mapValues(Outcome::event);

        // 4a. Global per-minute counts.
        fresh.mapValues(ActivityCounts::of)
                .groupBy((k, v) -> "all", Grouped.with("minute-all", strings, countsSerde))
                .windowedBy(TimeWindows.ofSizeAndGrace(Duration.ofMinutes(1), grace))
                .reduce(
                        ActivityCounts::plus,
                        Materialized.<String, ActivityCounts, WindowStore<Bytes, byte[]>>as("minute-counts")
                                .withKeySerde(strings)
                                .withValueSerde(countsSerde)
                                .withRetention(Duration.ofHours(2)))
                .toStream()
                .foreach((wk, counts) -> safely("minute aggregate", () ->
                        aggregateSink.minute(Instant.ofEpochMilli(wk.window().start()), counts)));

        // 4b/4c. Per repository, shared repartition for both window sizes.
        KGroupedStream<String, ActivityCounts> byRepo = fresh.map(
                        (k, e) -> KeyValue.pair(e.repoName(), ActivityCounts.of(e)))
                .groupByKey(Grouped.with("repo-activity", strings, countsSerde));

        byRepo.windowedBy(TimeWindows.ofSizeAndGrace(Duration.ofMinutes(1), grace))
                .reduce(
                        ActivityCounts::plus,
                        Materialized.<String, ActivityCounts, WindowStore<Bytes, byte[]>>as("repo-minute-counts")
                                .withKeySerde(strings)
                                .withValueSerde(countsSerde)
                                .withRetention(Duration.ofHours(2)))
                .toStream()
                .foreach((wk, counts) -> safely("repo aggregate", () ->
                        aggregateSink.repoMinute(Instant.ofEpochMilli(wk.window().start()), wk.key(), counts)));

        ProcessorSupplier<org.apache.kafka.streams.kstream.Windowed<String>, Long, Void, Void> momentum =
                () -> new MomentumProcessor(props.momentum(), aggregateSink);
        byRepo.windowedBy(TimeWindows.ofSizeAndGrace(props.momentum().window(), grace))
                .count(Materialized.<String, Long, WindowStore<Bytes, byte[]>>as("repo-window-counts")
                        .withKeySerde(strings)
                        .withValueSerde(Serdes.Long())
                        .withRetention(Duration.ofHours(2)))
                .toStream()
                .process(momentum, MOMENTUM_STORE, MOMENTUM_META);
    }

    private void sendToDlq(KStream<String, String> failures, String stage) {
        failures.peek((k, v) -> safely("failure sink", () -> failureSink.record(props.topics().dlq(), k, stage, v)))
                .to(props.topics().dlq(), Produced.with(Serdes.String(), Serdes.String()));
    }

    static ParseResult tryParse(String raw) {
        try {
            return new ParseResult(EventParser.parse(raw), null, raw);
        } catch (RuntimeException e) {
            log.warn("Malformed event routed to DLQ: {}", e.getMessage());
            return new ParseResult(null, e.getMessage(), raw);
        }
    }

    Outcome persistWithRetry(ParsedEvent event) {
        int attempts = props.streams().persistRetries() + 1;
        RuntimeException last = null;
        for (int i = 1; i <= attempts; i++) {
            try {
                return new Outcome(event, eventSink.persist(event), null);
            } catch (RuntimeException e) {
                last = e;
                log.warn("Persist attempt {}/{} failed for event {}: {}", i, attempts, event.id(), e.toString());
                if (i < attempts) {
                    try {
                        Thread.sleep(props.streams().retryBackoff().toMillis() * i);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        return new Outcome(event, null, last == null ? "interrupted" : last.toString());
    }

    private static String dlqRecord(String stage, String key, String reason, String payload) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("stage", stage);
        n.put("key", key);
        n.put("reason", reason);
        n.put("payload", payload);
        n.put("failedAt", Instant.now().toString());
        return n.toString();
    }

    private static String toJson(ParsedEvent e) {
        try {
            return MAPPER.writeValueAsString(e);
        } catch (Exception ex) {
            return String.valueOf(e);
        }
    }

    private static void safely(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Sink '{}' failed: {}", what, e.toString());
        }
    }
}
