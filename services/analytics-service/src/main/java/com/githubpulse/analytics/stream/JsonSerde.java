package com.githubpulse.analytics.stream;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

/** Minimal Jackson-backed serde for the small value types stored in state stores and repartition topics. */
public class JsonSerde<T> implements Serde<T> {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Class<T> type;

    public JsonSerde(Class<T> type) {
        this.type = type;
    }

    @Override
    public Serializer<T> serializer() {
        return (topic, data) -> {
            if (data == null) {
                return null;
            }
            try {
                return MAPPER.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new SerializationException("Cannot serialise " + type.getSimpleName(), e);
            }
        };
    }

    @Override
    public Deserializer<T> deserializer() {
        return (topic, bytes) -> {
            if (bytes == null) {
                return null;
            }
            try {
                return MAPPER.readValue(bytes, type);
            } catch (Exception e) {
                throw new SerializationException("Cannot deserialise " + type.getSimpleName(), e);
            }
        };
    }
}
