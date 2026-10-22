package com.githubpulse.ingestion.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.githubpulse.ingestion.config.IngestionProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes raw GitHub events to the shared raw topic. The record key is the GitHub event ID so that
 * Kafka Streams can deduplicate per partition. An {@code ingested_at} field is added to every event.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final IngestionProperties props;

    public EventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper, IngestionProperties props) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.props = props;
    }

    /**
     * Sends all events and waits for broker acknowledgement.
     *
     * @return the number of events acknowledged
     * @throws PublishException if any record could not be delivered; callers must not advance their
     *     ETag in that case so the events are fetched again (at-least-once)
     */
    public int publishAll(List<JsonNode> events) {
        Instant now = Instant.now();
        List<CompletableFuture<?>> futures = new ArrayList<>(events.size());
        for (JsonNode event : events) {
            String key = keyOf(event);
            String value = withIngestedAt(event, now);
            futures.add(kafka.send(props.rawTopic(), key, value));
        }
        kafka.flush();
        int acked = 0;
        try {
            for (CompletableFuture<?> f : futures) {
                f.get(props.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
                acked++;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishException("Interrupted while publishing to Kafka", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new PublishException("Kafka publish failed after " + acked + "/" + events.size() + " acks", e);
        }
        log.info("Published {} events to {}", acked, props.rawTopic());
        return acked;
    }

    static String keyOf(JsonNode event) {
        JsonNode id = event.get("id");
        if (id != null && !id.isNull() && !id.asText().isBlank()) {
            return id.asText();
        }
        // Events without an ID cannot be deduplicated; let the stream processor route them to the DLQ.
        return "no-id-" + UUID.randomUUID();
    }

    private String withIngestedAt(JsonNode event, Instant now) {
        try {
            if (event instanceof ObjectNode obj) {
                ObjectNode copy = obj.deepCopy();
                copy.put("ingested_at", now.toString());
                return mapper.writeValueAsString(copy);
            }
            return mapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new PublishException("Cannot serialise event", e);
        }
    }

    public static class PublishException extends RuntimeException {
        public PublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
