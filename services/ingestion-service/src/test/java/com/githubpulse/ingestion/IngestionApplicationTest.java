package com.githubpulse.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.githubpulse.ingestion.grpc.IngestionGrpcService;
import com.githubpulse.ingestion.status.IngestionStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Verifies that the wiring is valid: polling is off and gRPC binds an ephemeral port. */
@SpringBootTest(
        properties = {
            "pulse.ingestion.polling-enabled=false",
            "pulse.ingestion.grpc-port=0",
            "spring.kafka.bootstrap-servers=localhost:1"
        })
class IngestionApplicationTest {

    @Autowired
    IngestionGrpcService grpc;

    @Autowired
    IngestionStatusService status;

    @Test
    void contextLoads() {
        assertThat(grpc).isNotNull();
        assertThat(status.snapshot().eventsIngested()).isZero();
    }
}
