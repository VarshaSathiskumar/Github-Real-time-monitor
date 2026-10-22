package com.githubpulse.analytics.api;

import com.githubpulse.analytics.api.Dtos.SystemStatus;
import com.githubpulse.analytics.demo.DemoSimulator;
import com.githubpulse.analytics.model.Mode;
import com.githubpulse.analytics.service.SystemStatusService;
import com.githubpulse.analytics.store.ProcessingFailure;
import com.githubpulse.analytics.store.ProcessingFailureRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "System", description = "Pipeline health and demo mode")
public class SystemController {

    public record FailureView(Long id, String topic, String key, String stage, String payload, Instant failedAt) {}

    private final SystemStatusService status;
    private final ProcessingFailureRepository failures;
    private final DemoSimulator demo;

    public SystemController(SystemStatusService status, ProcessingFailureRepository failures, DemoSimulator demo) {
        this.status = status;
        this.failures = failures;
        this.demo = demo;
    }

    @GetMapping("/system/status")
    @Operation(summary = "Ingestion health (via gRPC), stream processor state, Redis, PostgreSQL and consumer lag")
    public SystemStatus status(@RequestParam(defaultValue = "live") String mode) {
        return status.status(Mode.parse(mode));
    }

    @GetMapping("/system/failures")
    @Operation(summary = "The latest events routed to the dead-letter topic")
    public List<FailureView> failures() {
        return failures.findTop20ByOrderByFailedAtDesc().stream()
                .map(f -> new FailureView(
                        f.getId(), f.getTopic(), f.getRecordKey(), f.getStage(), f.getPayload(), f.getFailedAt()))
                .toList();
    }

    @PostMapping("/demo/start")
    @Operation(summary = "Start (or keep alive) the simulated Demo Data workload. Never mixed with live metrics.")
    public SystemStatus startDemo() {
        demo.ensureRunning();
        return status.status(Mode.DEMO);
    }

    @PostMapping("/demo/stop")
    @Operation(summary = "Stop the demo simulator and discard its data")
    public SystemStatus stopDemo() {
        demo.stop();
        return status.status(Mode.LIVE);
    }
}
