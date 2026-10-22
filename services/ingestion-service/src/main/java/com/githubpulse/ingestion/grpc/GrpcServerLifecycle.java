package com.githubpulse.ingestion.grpc;

import com.githubpulse.ingestion.config.IngestionProperties;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Starts the gRPC server with the application and drains it on shutdown. */
@Component
public class GrpcServerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);

    private final Server server;
    private volatile boolean running;

    public GrpcServerLifecycle(IngestionProperties props, IngestionGrpcService service) {
        this.server = ServerBuilder.forPort(props.grpcPort()).addService(service).build();
    }

    @Override
    public void start() {
        try {
            server.start();
            running = true;
            log.info("gRPC server listening on port {}", server.getPort());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start gRPC server", e);
        }
    }

    @Override
    public void stop() {
        running = false;
        server.shutdown();
        try {
            if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                server.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
