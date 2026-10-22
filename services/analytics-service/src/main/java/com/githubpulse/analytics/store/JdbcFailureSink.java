package com.githubpulse.analytics.store;

import com.githubpulse.analytics.stream.Sinks;
import org.springframework.stereotype.Component;

/** Keeps a queryable copy of everything routed to the dead-letter topic. */
@Component
public class JdbcFailureSink implements Sinks.FailureSink {

    private final ProcessingFailureRepository repository;

    public JdbcFailureSink(ProcessingFailureRepository repository) {
        this.repository = repository;
    }

    @Override
    public void record(String topic, String key, String reason, String payload) {
        String shortPayload = payload != null && payload.length() > 8000 ? payload.substring(0, 8000) : payload;
        repository.save(new ProcessingFailure(topic, key, reason, shortPayload));
    }
}
