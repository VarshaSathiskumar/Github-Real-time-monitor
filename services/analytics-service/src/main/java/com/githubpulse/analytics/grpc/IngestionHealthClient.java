package com.githubpulse.analytics.grpc;

import com.githubpulse.analytics.api.Dtos.IngestionInfo;
import com.githubpulse.analytics.config.PulseProperties;
import com.githubpulse.proto.ingestion.v1.GetIngestionStatusRequest;
import com.githubpulse.proto.ingestion.v1.GetIngestionStatusResponse;
import com.githubpulse.proto.ingestion.v1.IngestionServiceGrpc;
import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Asks the ingestion service for its health over gRPC. Event delivery stays on Kafka. */
@Component
public class IngestionHealthClient {

    private static final Logger log = LoggerFactory.getLogger(IngestionHealthClient.class);

    private final ManagedChannel channel;
    private final long deadlineMillis;

    @Autowired
    public IngestionHealthClient(PulseProperties props) {
        this(
                ManagedChannelBuilder.forTarget(props.ingestionGrpc().target()).usePlaintext().build(),
                props.ingestionGrpc().deadline().toMillis());
    }

    public IngestionHealthClient(ManagedChannel channel, long deadlineMillis) {
        this.channel = channel;
        this.deadlineMillis = deadlineMillis;
    }

    public IngestionInfo fetch() {
        try {
            GetIngestionStatusResponse r = IngestionServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
                    .getIngestionStatus(GetIngestionStatusRequest.getDefaultInstance());
            return new IngestionInfo(
                    true,
                    r.getStatus().name().replace("INGESTION_STATE_", ""),
                    r.hasLastSuccessfulPoll() ? toInstant(r.getLastSuccessfulPoll()) : null,
                    r.hasLastPollAttempt() ? toInstant(r.getLastPollAttempt()) : null,
                    r.getEventsIngested(),
                    r.getLastError().isEmpty() ? null : r.getLastError(),
                    r.getConsecutiveFailures(),
                    r.getPollIntervalSeconds(),
                    r.getRateLimitRemaining());
        } catch (StatusRuntimeException e) {
            log.debug("Ingestion service unreachable: {}", e.getStatus());
            return new IngestionInfo(
                    false, "UNREACHABLE", null, null, 0, "gRPC " + e.getStatus().getCode(), 0, 0, -1);
        }
    }

    private static Instant toInstant(Timestamp t) {
        return Instant.ofEpochSecond(t.getSeconds(), t.getNanos());
    }

    @PreDestroy
    void shutdown() {
        channel.shutdown();
    }
}
