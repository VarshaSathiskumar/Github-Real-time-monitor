package com.githubpulse.analytics.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "processing_failures")
public class ProcessingFailure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String topic;

    @Column(name = "record_key")
    private String recordKey;

    @Column(nullable = false)
    private String stage;

    @Column(columnDefinition = "text")
    private String payload;

    @Column(name = "failed_at", nullable = false)
    private Instant failedAt = Instant.now();

    protected ProcessingFailure() {}

    public ProcessingFailure(String topic, String recordKey, String stage, String payload) {
        this.topic = topic;
        this.recordKey = recordKey;
        this.stage = stage;
        this.payload = payload;
    }

    public Long getId() {
        return id;
    }

    public String getTopic() {
        return topic;
    }

    public String getRecordKey() {
        return recordKey;
    }

    public String getStage() {
        return stage;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getFailedAt() {
        return failedAt;
    }
}
