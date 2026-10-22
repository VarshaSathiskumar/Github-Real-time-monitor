package com.githubpulse.analytics.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.githubpulse.analytics.TestProps;
import com.githubpulse.analytics.config.PulseProperties;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs the topology against a real Kafka broker. By default a Testcontainers broker is started (needs
 * Docker). Set {@code -Dit.kafka=host:port} to use an already running broker instead.
 */
@EnabledIf("kafkaAvailable")
class KafkaPipelineIT {

    private static KafkaContainer container;
    private static String bootstrap;

    static boolean kafkaAvailable() {
        return System.getProperty("it.kafka") != null || DockerClientFactory.instance().isDockerAvailable();
    }

    @BeforeAll
    static void startBroker() {
        bootstrap = System.getProperty("it.kafka");
        if (bootstrap == null) {
            container = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
            container.start();
            bootstrap = container.getBootstrapServers();
        }
    }

    @AfterAll
    static void stopBroker() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void processesRealKafkaRecordsEndToEnd() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String raw = "it.raw." + suffix;
        String dlq = "it.dlq." + suffix;
        PulseProperties props = TestProps.create(raw, dlq);

        try (Admin admin = Admin.create(Map.of("bootstrap.servers", bootstrap))) {
            admin.createTopics(List.of(new NewTopic(raw, 2, (short) 1), new NewTopic(dlq, 1, (short) 1))).all().get();
        }

        var events = new RecordingSinks.Events();
        var aggregates = new RecordingSinks.Aggregates();
        var failures = new RecordingSinks.Failures();
        StreamsBuilder builder = new StreamsBuilder();
        new PulseTopology(props, events, aggregates, failures).build(builder);

        Properties sp = new Properties();
        sp.put(StreamsConfig.APPLICATION_ID_CONFIG, "it-" + suffix);
        sp.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        sp.put(StreamsConfig.STATE_DIR_CONFIG, Files.createTempDirectory("it-state").toString());
        sp.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 200);
        sp.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 1);
        sp.put(StreamsConfig.consumerPrefix(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG), "earliest");
        sp.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, org.apache.kafka.common.serialization.Serdes.StringSerde.class);
        sp.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, org.apache.kafka.common.serialization.Serdes.StringSerde.class);

        try (KafkaStreams streams = new KafkaStreams(builder.build(), sp)) {
            streams.start();

            Properties pp = new Properties();
            pp.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            pp.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            pp.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            try (KafkaProducer<String, String> producer = new KafkaProducer<>(pp)) {
                for (long id = 1; id <= 20; id++) {
                    producer.send(new ProducerRecord<>(raw, Long.toString(id), TestProps.pushJson(id, "it/repo" + (id % 3))));
                }
                // redelivery of ids 1..5 (at-least-once producer retry)
                for (long id = 1; id <= 5; id++) {
                    producer.send(new ProducerRecord<>(raw, Long.toString(id), TestProps.pushJson(id, "it/repo" + (id % 3))));
                }
                producer.send(new ProducerRecord<>(raw, "bad-1", "{definitely not an event"));
                producer.flush();
            }

            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                long total;
                synchronized (aggregates.minutes) {
                    total = aggregates.minutes.values().stream().mapToLong(c -> c.total()).sum();
                }
                assertThat(total).isEqualTo(20);
            });
        }

        assertThat(events.persisted).hasSize(20);
        assertThat(readAll(dlq)).hasSize(1);
        assertThat(failures.records).hasSize(1);
    }

    private static List<String> readAll(String topic) {
        Properties cp = new Properties();
        cp.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        cp.put(ConsumerConfig.GROUP_ID_CONFIG, "reader-" + UUID.randomUUID());
        cp.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        cp.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cp.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<String> out = new ArrayList<>();
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(cp)) {
            c.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 15_000;
            while (out.isEmpty() && System.currentTimeMillis() < deadline) {
                c.poll(Duration.ofMillis(500)).forEach(r -> out.add(r.value()));
            }
        }
        return out;
    }
}
