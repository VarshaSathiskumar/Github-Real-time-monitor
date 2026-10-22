package com.githubpulse.analytics.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.githubpulse.analytics.api.Dtos.IngestionInfo;
import com.githubpulse.proto.ingestion.v1.GetIngestionStatusRequest;
import com.githubpulse.proto.ingestion.v1.GetIngestionStatusResponse;
import com.githubpulse.proto.ingestion.v1.IngestionServiceGrpc;
import com.githubpulse.proto.ingestion.v1.IngestionState;
import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class IngestionHealthClientTest {

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    @Test
    void mapsIngestionStatusFromGrpc() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(new IngestionServiceGrpc.IngestionServiceImplBase() {
                    @Override
                    public void getIngestionStatus(
                            GetIngestionStatusRequest request, StreamObserver<GetIngestionStatusResponse> out) {
                        out.onNext(GetIngestionStatusResponse.newBuilder()
                                .setStatus(IngestionState.INGESTION_STATE_RATE_LIMITED)
                                .setEventsIngested(1234)
                                .setLastError("rate limited")
                                .setLastSuccessfulPoll(Timestamp.newBuilder().setSeconds(1_700_000_000))
                                .setPollIntervalSeconds(60)
                                .setRateLimitRemaining(7)
                                .build());
                        out.onCompleted();
                    }
                })
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        IngestionInfo info = new IngestionHealthClient(channel, 1000).fetch();

        assertThat(info.reachable()).isTrue();
        assertThat(info.status()).isEqualTo("RATE_LIMITED");
        assertThat(info.eventsIngested()).isEqualTo(1234);
        assertThat(info.lastError()).isEqualTo("rate limited");
        assertThat(info.lastSuccessfulPoll().getEpochSecond()).isEqualTo(1_700_000_000L);
        assertThat(info.lastPollAttempt()).isNull();
        assertThat(info.rateLimitRemaining()).isEqualTo(7);
    }

    @Test
    void reportsUnreachableInsteadOfThrowingWhenServiceIsDown() {
        channel = InProcessChannelBuilder.forName("nobody-listens-here").directExecutor().build();

        IngestionInfo info = new IngestionHealthClient(channel, 200).fetch();

        assertThat(info.reachable()).isFalse();
        assertThat(info.status()).isEqualTo("UNREACHABLE");
        assertThat(info.lastError()).contains("UNAVAILABLE");
    }
}
