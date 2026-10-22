package com.githubpulse.analytics.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.githubpulse.analytics.TestProps;
import com.githubpulse.analytics.config.PulseProperties;
import com.githubpulse.analytics.model.ActivityCounts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PulseTopologyTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PulseProperties props = TestProps.create();
    private final RecordingSinks.Events events = new RecordingSinks.Events();
    private final RecordingSinks.Aggregates aggregates = new RecordingSinks.Aggregates();
    private final RecordingSinks.Failures failures = new RecordingSinks.Failures();

    private TopologyTestDriver driver;
    private TestInputTopic<String, String> raw;
    private TestOutputTopic<String, String> dlq;

    @BeforeEach
    void setUp(@TempDir Path stateDir) throws Exception {
        StreamsBuilder builder = new StreamsBuilder();
        new PulseTopology(props, events, aggregates, failures).build(builder);

        Properties p = new Properties();
        p.put(StreamsConfig.APPLICATION_ID_CONFIG, "topology-test");
        p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        p.put(StreamsConfig.STATE_DIR_CONFIG, Files.createTempDirectory(stateDir, "state").toString());
        p.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        p.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        p.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, 0);
        driver = new TopologyTestDriver(builder.build(), p);

        raw = driver.createInputTopic(props.topics().raw(), new StringSerializer(), new StringSerializer());
        dlq = driver.createOutputTopic(props.topics().dlq(), new StringDeserializer(), new StringDeserializer());
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    private void send(long id, String json, Instant at) {
        raw.pipeInput(Long.toString(id), json, at);
    }

    @Test
    void countsEventsPerMinuteByKind() {
        send(1, TestProps.pushJson(1, "a/one"), T0.plusSeconds(1));
        send(2, TestProps.pushJson(2, "a/one"), T0.plusSeconds(2));
        send(3, TestProps.eventJson(3, "PullRequestEvent", "b/two", "x", "2026-01-01T00:00:00Z", "{}"), T0.plusSeconds(3));
        send(4, TestProps.eventJson(4, "WatchEvent", "b/two", "x", "2026-01-01T00:00:00Z", "{}"), T0.plusSeconds(61));

        ActivityCounts first = aggregates.minutes.get(T0);
        ActivityCounts second = aggregates.minutes.get(T0.plusSeconds(60));
        assertThat(first.total()).isEqualTo(3);
        assertThat(first.push()).isEqualTo(2);
        assertThat(first.pullRequest()).isEqualTo(1);
        assertThat(second.total()).isEqualTo(1);
        assertThat(second.watch()).isEqualTo(1);
    }

    @Test
    void aggregatesPerRepositoryAndMinute() {
        send(1, TestProps.pushJson(1, "a/one"), T0.plusSeconds(1));
        send(2, TestProps.pushJson(2, "a/one"), T0.plusSeconds(2));
        send(3, TestProps.pushJson(3, "b/two"), T0.plusSeconds(3));

        assertThat(aggregates.repoAt("a/one", T0).total()).isEqualTo(2);
        assertThat(aggregates.repoAt("b/two", T0).total()).isEqualTo(1);
    }

    @Test
    void duplicateEventIdIsPersistedAndCountedOnce() {
        send(42, TestProps.pushJson(42, "a/one"), T0.plusSeconds(1));
        send(42, TestProps.pushJson(42, "a/one"), T0.plusSeconds(5));
        send(42, TestProps.pushJson(42, "a/one"), T0.plusSeconds(9));

        assertThat(events.persisted).hasSize(1);
        assertThat(aggregates.minutes.get(T0).total()).isEqualTo(1);
    }

    @Test
    void usesKafkaRecordTimestampAsObservationTime() {
        send(1, TestProps.pushJson(1, "a/one"), T0.plusSeconds(30));

        assertThat(events.persisted.get(0).observedAt()).isEqualTo(T0.plusSeconds(30));
    }

    @Test
    void malformedEventsGoToDeadLetterTopicWithoutStoppingTheStream() throws Exception {
        send(1, "this is not json", T0.plusSeconds(1));
        send(2, "{\"id\":\"2\",\"type\":\"PushEvent\"}", T0.plusSeconds(2));
        send(3, TestProps.pushJson(3, "a/one"), T0.plusSeconds(3));

        List<String> dead = dlq.readValuesToList();
        assertThat(dead).hasSize(2);
        JsonNode first = MAPPER.readTree(dead.get(0));
        assertThat(first.get("stage").asText()).isEqualTo("parse");
        assertThat(first.get("payload").asText()).isEqualTo("this is not json");
        assertThat(failures.records).containsExactly("parse:1", "parse:2");
        // the valid event after the poison pills is still processed
        assertThat(aggregates.minutes.get(T0).total()).isEqualTo(1);
    }

    @Test
    void persistenceFailureIsRetriedThenSentToDeadLetterTopic() throws Exception {
        events.failuresLeft.set(100);
        send(9, TestProps.pushJson(9, "a/one"), T0.plusSeconds(1));

        assertThat(events.attempts.get()).isEqualTo(props.streams().persistRetries() + 1);
        List<String> dead = dlq.readValuesToList();
        assertThat(dead).hasSize(1);
        assertThat(MAPPER.readTree(dead.get(0)).get("stage").asText()).isEqualTo("persist");
        assertThat(aggregates.minutes).isEmpty();
    }

    @Test
    void transientPersistenceFailureRecoversWithoutDeadLettering() {
        events.failuresLeft.set(1);
        send(9, TestProps.pushJson(9, "a/one"), T0.plusSeconds(1));

        assertThat(dlq.isEmpty()).isTrue();
        assertThat(aggregates.minutes.get(T0).total()).isEqualTo(1);
    }

    @Test
    void eventsAlreadyStoredInDatabaseDoNotInflateAggregates() {
        events.alreadyInDatabase.add(5L);
        send(5, TestProps.pushJson(5, "a/one"), T0.plusSeconds(1));
        send(6, TestProps.pushJson(6, "a/one"), T0.plusSeconds(2));

        assertThat(aggregates.minutes.get(T0).total()).isEqualTo(1);
    }

    @Test
    void detectsActivitySpikeAgainstRecentBaseline() {
        long id = 1;
        // Four quiet windows of 2 events each for the hot repository, then a burst of 10.
        for (int window = 0; window < 4; window++) {
            Instant start = T0.plus(Duration.ofMinutes(5L * window));
            for (int i = 0; i < 2; i++) {
                send(id, TestProps.pushJson(id, "hot/repo"), start.plusSeconds(10 + i));
                id++;
            }
        }
        assertThat(aggregates.spikes).isEmpty();

        Instant burst = T0.plus(Duration.ofMinutes(20));
        for (int i = 0; i < 10; i++) {
            send(id, TestProps.pushJson(id, "hot/repo"), burst.plusSeconds(5 + i));
            id++;
        }

        assertThat(aggregates.spikes).isNotEmpty();
        var spike = aggregates.spikes.get(aggregates.spikes.size() - 1);
        assertThat(spike.repo()).isEqualTo("hot/repo");
        assertThat(spike.currentCount()).isEqualTo(10);
        assertThat(spike.baseline()).isEqualTo(2.0);
        assertThat(spike.pctIncrease()).isEqualTo(400.0);
        assertThat(spike.level()).isEqualTo("RISING");
        assertThat(spike.windowStart()).isEqualTo(burst);
    }

    @Test
    void newRepositoryBurstWithoutBaselineHasNoPercentage() {
        long id = 1;
        // Other repositories establish three windows of history.
        for (int window = 0; window < 4; window++) {
            send(id, TestProps.pushJson(id, "quiet/repo"), T0.plus(Duration.ofMinutes(5L * window)).plusSeconds(1));
            id++;
        }
        Instant burst = T0.plus(Duration.ofMinutes(20));
        for (int i = 0; i < 9; i++) {
            send(id, TestProps.pushJson(id, "brand/new"), burst.plusSeconds(1 + i));
            id++;
        }

        var spike = aggregates.spikes.stream()
                .filter(s -> s.repo().equals("brand/new"))
                .reduce((a, b) -> b)
                .orElseThrow();
        assertThat(spike.pctIncrease()).isNull();
        assertThat(spike.level()).isEqualTo("BURST");
    }
}
