package com.githubpulse.analytics.api;

import com.githubpulse.analytics.api.Dtos.ActivityResponse;
import com.githubpulse.analytics.api.Dtos.BreakdownResponse;
import com.githubpulse.analytics.api.Dtos.EventsResponse;
import com.githubpulse.analytics.api.Dtos.MetricsResponse;
import com.githubpulse.analytics.api.Dtos.RisingResponse;
import com.githubpulse.analytics.api.Dtos.TrendingResponse;
import com.githubpulse.analytics.model.Mode;
import com.githubpulse.analytics.model.Range;
import com.githubpulse.analytics.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Initial-load endpoints. Live updates afterwards arrive over the {@code /ws} WebSocket. */
@RestController
@RequestMapping("/api")
@Tag(name = "Dashboard", description = "Aggregated views of observed GitHub public activity")
public class DashboardController {

    private static final String MODE_DOC = "live (real GitHub events, default) or demo (simulated, labelled Demo Data)";
    private static final String RANGE_DOC = "Observation window: 15m, 1h (default), 6h or 24h";

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping("/metrics")
    @Operation(summary = "Six KPI cards with sparklines and change versus the previous equivalent window")
    public MetricsResponse metrics(
            @RequestParam(defaultValue = "1h") @Parameter(description = RANGE_DOC) String range,
            @RequestParam(defaultValue = "live") @Parameter(description = MODE_DOC) String mode) {
        return service.metrics(Mode.parse(mode), Range.parse(range));
    }

    @GetMapping("/activity")
    @Operation(summary = "Events per minute over time (total, push, pull request)")
    public ActivityResponse activity(
            @RequestParam(defaultValue = "1h") @Parameter(description = RANGE_DOC) String range,
            @RequestParam(defaultValue = "live") @Parameter(description = MODE_DOC) String mode) {
        return service.activity(Mode.parse(mode), Range.parse(range));
    }

    @GetMapping("/activity/breakdown")
    @Operation(summary = "Distribution of event types")
    public BreakdownResponse breakdown(
            @RequestParam(defaultValue = "1h") @Parameter(description = RANGE_DOC) String range,
            @RequestParam(defaultValue = "live") @Parameter(description = MODE_DOC) String mode) {
        return service.breakdown(Mode.parse(mode), Range.parse(range));
    }

    @GetMapping("/repositories/trending")
    @Operation(summary = "Five most active repositories in the observed sample, ranked by activity score")
    public TrendingResponse trending(
            @RequestParam(defaultValue = "1h") @Parameter(description = RANGE_DOC) String range,
            @RequestParam(defaultValue = "live") @Parameter(description = MODE_DOC) String mode) {
        return service.trending(Mode.parse(mode), Range.parse(range));
    }

    @GetMapping("/repositories/rising")
    @Operation(summary = "Repositories with an activity spike versus their recent baseline (momentum detector)")
    public RisingResponse rising(@RequestParam(defaultValue = "live") @Parameter(description = MODE_DOC) String mode) {
        return service.rising(Mode.parse(mode));
    }

    @GetMapping("/events/recent")
    @Operation(summary = "Most recent processed events, newest first")
    public EventsResponse recent(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "live") @Parameter(description = MODE_DOC) String mode) {
        return service.recentEvents(Mode.parse(mode), Math.max(1, Math.min(limit, 100)));
    }
}
