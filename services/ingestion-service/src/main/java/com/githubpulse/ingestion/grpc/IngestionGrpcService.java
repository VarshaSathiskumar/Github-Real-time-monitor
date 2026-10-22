package com.githubpulse.ingestion.grpc;

import com.githubpulse.ingestion.status.IngestionStatusService;
import com.githubpulse.proto.ingestion.v1.GetIngestionStatusRequest;
import com.githubpulse.proto.ingestion.v1.GetIngestionStatusResponse;
import com.githubpulse.proto.ingestion.v1.IngestionServiceGrpc;
import com.githubpulse.proto.ingestion.v1.IngestionState;
import com.google.protobuf.Timestamp;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class IngestionGrpcService extends IngestionServiceGrpc.IngestionServiceImplBase {

    private final IngestionStatusService status;

    public IngestionGrpcService(IngestionStatusService status) {
        this.status = status;
    }

    @Override
    public void getIngestionStatus(
            GetIngestionStatusRequest request, StreamObserver<GetIngestionStatusResponse> observer) {
        var s = status.snapshot();
        GetIngestionStatusResponse.Builder b = GetIngestionStatusResponse.newBuilder()
                .setEventsIngested(s.eventsIngested())
                .setStatus(toProto(s.state()))
                .setLastError(s.lastError() == null ? "" : s.lastError())
                .setConsecutiveFailures(s.consecutiveFailures())
                .setPollIntervalSeconds(s.pollIntervalSeconds())
                .setRateLimitRemaining(s.rateLimitRemaining());
        if (s.lastSuccessfulPoll() != null) {
            b.setLastSuccessfulPoll(toTimestamp(s.lastSuccessfulPoll()));
        }
        if (s.lastPollAttempt() != null) {
            b.setLastPollAttempt(toTimestamp(s.lastPollAttempt()));
        }
        observer.onNext(b.build());
        observer.onCompleted();
    }

    static IngestionState toProto(com.githubpulse.ingestion.status.IngestionState state) {
        return switch (state) {
            case STARTING -> IngestionState.INGESTION_STATE_STARTING;
            case HEALTHY -> IngestionState.INGESTION_STATE_HEALTHY;
            case DEGRADED -> IngestionState.INGESTION_STATE_DEGRADED;
            case RATE_LIMITED -> IngestionState.INGESTION_STATE_RATE_LIMITED;
            case FAILING -> IngestionState.INGESTION_STATE_FAILING;
            case STOPPED -> IngestionState.INGESTION_STATE_STOPPED;
        };
    }

    private static Timestamp toTimestamp(Instant i) {
        return Timestamp.newBuilder().setSeconds(i.getEpochSecond()).setNanos(i.getNano()).build();
    }
}
