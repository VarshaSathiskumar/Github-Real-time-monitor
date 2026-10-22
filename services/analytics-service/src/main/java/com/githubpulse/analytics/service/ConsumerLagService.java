package com.githubpulse.analytics.service;

import com.githubpulse.analytics.config.PulseProperties;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Service;

/** Consumer lag of the stream processor on the raw topic: log end offset minus committed offset. */
@Service
public class ConsumerLagService {

    private static final Logger log = LoggerFactory.getLogger(ConsumerLagService.class);

    private final KafkaAdmin kafkaAdmin;
    private final String groupId;
    private final String topic;
    private Admin admin;
    private long cachedAt;
    private Long cachedLag;

    public ConsumerLagService(
            KafkaAdmin kafkaAdmin,
            PulseProperties props,
            @Value("${spring.kafka.streams.application-id}") String applicationId) {
        this.kafkaAdmin = kafkaAdmin;
        this.groupId = applicationId;
        this.topic = props.topics().raw();
    }

    /** @return total lag across partitions, or null when it cannot be determined (no commits yet, broker down) */
    public synchronized Long totalLag() {
        long now = System.nanoTime();
        if (now - cachedAt < Duration.ofSeconds(5).toNanos()) {
            return cachedLag;
        }
        cachedAt = now;
        try {
            if (admin == null) {
                admin = Admin.create(new HashMap<>(kafkaAdmin.getConfigurationProperties()));
            }
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata()
                    .get(3, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetSpec> request = new HashMap<>();
            committed.keySet().stream().filter(tp -> tp.topic().equals(topic)).forEach(tp -> request.put(tp, OffsetSpec.latest()));
            if (request.isEmpty()) {
                cachedLag = null;
                return null;
            }
            var ends = admin.listOffsets(request).all().get(3, TimeUnit.SECONDS);
            long lag = 0;
            for (var e : ends.entrySet()) {
                lag += Math.max(0, e.getValue().offset() - committed.get(e.getKey()).offset());
            }
            cachedLag = lag;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("Consumer lag unavailable: {}", e.toString());
            cachedLag = null;
        }
        return cachedLag;
    }

    @PreDestroy
    synchronized void close() {
        if (admin != null) {
            admin.close(Duration.ofSeconds(2));
        }
    }
}
