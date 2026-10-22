package com.githubpulse.analytics.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.githubpulse.analytics.api.Dtos.BreakdownItem;
import com.githubpulse.analytics.api.Dtos.BreakdownResponse;
import com.githubpulse.analytics.api.Dtos.Kpi;
import com.githubpulse.analytics.api.Dtos.MetricsResponse;
import com.githubpulse.analytics.api.Dtos.RisingResponse;
import com.githubpulse.analytics.model.EventKind;
import com.githubpulse.analytics.model.Mode;
import com.githubpulse.analytics.model.Range;
import com.githubpulse.analytics.service.DashboardService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DashboardController.class)
class DashboardControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    DashboardService service;

    private static Kpi kpi(Long v) {
        return new Kpi(v, 12.5, List.of(1L, 2L), null, "basis");
    }

    @Test
    void metricsDefaultsToLiveOneHour() throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        when(service.metrics(eq(Mode.LIVE), eq(Range.H1)))
                .thenReturn(new MetricsResponse(
                        "live", "1h", now, now, kpi(100L), kpi(40L), kpi(60L), new Kpi(null, null, List.of(), "n/a", null),
                        kpi(5L), kpi(2L), now));

        mvc.perform(get("/api/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("live"))
                .andExpect(jsonPath("$.totalEvents.value").value(100))
                .andExpect(jsonPath("$.totalEvents.changePct").value(12.5))
                .andExpect(jsonPath("$.commits.value").doesNotExist())
                .andExpect(jsonPath("$.commits.note").value("n/a"));
    }

    @Test
    void passesRangeAndModeThrough() throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        when(service.breakdown(eq(Mode.DEMO), eq(Range.H6)))
                .thenReturn(new BreakdownResponse(
                        "demo", "6h", 10, List.of(new BreakdownItem(EventKind.PUSH, "Push events", 10, 100.0)), now));

        mvc.perform(get("/api/activity/breakdown").param("range", "6h").param("mode", "demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].kind").value("PUSH"))
                .andExpect(jsonPath("$.items[0].percent").value(100.0));
    }

    @Test
    void rejectsUnknownRangeWithBadRequest() throws Exception {
        mvc.perform(get("/api/metrics").param("range", "3d"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unsupported range '3d', expected 15m, 1h, 6h or 24h"));
    }

    @Test
    void rejectsUnknownMode() throws Exception {
        mvc.perform(get("/api/repositories/trending").param("mode", "fake")).andExpect(status().isBadRequest());
    }

    @Test
    void risingCarriesDisclaimer() throws Exception {
        when(service.rising(Mode.LIVE))
                .thenReturn(new RisingResponse("live", 5, 6, 8, "observed sample only", List.of(), Instant.EPOCH));

        mvc.perform(get("/api/repositories/rising"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disclaimer").value("observed sample only"))
                .andExpect(jsonPath("$.windowMinutes").value(5));
    }

    @Test
    void recentEventsLimitIsClamped() throws Exception {
        when(service.recentEvents(Mode.LIVE, 100))
                .thenReturn(new Dtos.EventsResponse("live", List.of(), Instant.EPOCH));

        mvc.perform(get("/api/events/recent").param("limit", "9999")).andExpect(status().isOk());
    }
}
