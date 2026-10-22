package com.githubpulse.ingestion.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.githubpulse.ingestion.status.IngestionStatusService;
import com.githubpulse.proto.ingestion.v1.GetIngestionStatusRequest;
import com.githubpulse.proto.ingestion.v1.GetIngestionStatusResponse;
import com.githubpulse.proto.ingestion.v1.IngestionServiceGrpc;
import com.githubpulse.proto.ingestion.v1.IngestionState;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IngestionGrpcServiceTest {

    private IngestionStatusService status;
    private Server server;
    private ManagedChannel channel;
    private IngestionServiceGrpc.IngestionServiceBlockingStub stub;

    @BeforeEach
    void setUp() throws Exception {
        status = new IngestionStatusService();
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(new IngestionGrpcService(status))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        stub = IngestionServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void reportsStartingStateBeforeFirstPoll() {
        GetIngestionStatusResponse res = stub.getIngestionStatus(GetIngestionStatusRequest.getDefaultInstance());

        assertThat(res.getStatus()).isEqualTo(IngestionState.INGESTION_STATE_STARTING);
        assertThat(res.hasLastSuccessfulPoll()).isFalse();
        assertThat(res.getEventsIngested()).isZero();
    }

    @Test
    void reportsHealthyAfterSuccessfulPoll() {
        status.pollAttempted();
        status.pollSucceeded(25, 60, 4999);

        GetIngestionStatusResponse res = stub.getIngestionStatus(GetIngestionStatusRequest.getDefaultInstance());

        assertThat(res.getStatus()).isEqualTo(IngestionState.INGESTION_STATE_HEALTHY);
        assertThat(res.getEventsIngested()).isEqualTo(25);
        assertThat(res.hasLastSuccessfulPoll()).isTrue();
        assertThat(res.getRateLimitRemaining()).isEqualTo(4999);
        assertThat(res.getLastError()).isEmpty();
    }

    @Test
    void reportsLastErrorAndEscalatesToFailing() {
        status.pollFailed("boom 1");
        status.pollFailed("boom 2");
        status.pollFailed("boom 3");

        GetIngestionStatusResponse res = stub.getIngestionStatus(GetIngestionStatusRequest.getDefaultInstance());

        assertThat(res.getStatus()).isEqualTo(IngestionState.INGESTION_STATE_FAILING);
        assertThat(res.getLastError()).isEqualTo("boom 3");
        assertThat(res.getConsecutiveFailures()).isEqualTo(3);
    }
}
