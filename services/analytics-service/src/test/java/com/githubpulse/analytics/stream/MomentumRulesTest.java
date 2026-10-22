package com.githubpulse.analytics.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.githubpulse.analytics.TestProps;
import com.githubpulse.analytics.config.PulseProperties;
import com.githubpulse.analytics.model.RisingRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MomentumRulesTest {

    private static final PulseProperties.Momentum CFG = TestProps.create().momentum();
    private static final long W = CFG.window().toMillis();
    private static final long FIRST = 0;

    private Optional<RisingRepository> eval(long windowIndex, long count, Map<Long, Long> history) {
        return MomentumRules.evaluate(CFG, "octo/repo", windowIndex * W, count, history, FIRST, Instant.EPOCH);
    }

    @Test
    void detectsTwoEventsPerWindowJumpingToTwenty() {
        Map<Long, Long> history = Map.of(3 * W, 2L, 2 * W, 2L, 1 * W, 2L);

        RisingRepository r = eval(4, 20, history).orElseThrow();

        assertThat(r.baseline()).isEqualTo(2.0);
        assertThat(r.currentCount()).isEqualTo(20);
        assertThat(r.pctIncrease()).isEqualTo(900.0);
        assertThat(r.level()).isEqualTo("SURGING");
        assertThat(r.windowMinutes()).isEqualTo(5);
    }

    @Test
    void ignoresRepositoriesBelowMinimumEventCount() {
        assertThat(eval(4, 4, Map.of(3 * W, 0L, 2 * W, 0L, 1 * W, 0L))).isEmpty();
    }

    @Test
    void ignoresIncreasesSmallerThanSpikeRatio() {
        // baseline 4, current 8 is only 2x
        assertThat(eval(4, 8, Map.of(3 * W, 4L, 2 * W, 4L, 1 * W, 4L))).isEmpty();
    }

    @Test
    void waitsUntilEnoughHistoryHasBeenObserved() {
        assertThat(eval(1, 50, Map.of())).isEmpty();
    }

    @Test
    void zeroBaselineIsLabelledBurstWithoutPercentage() {
        RisingRepository r = eval(4, 12, Map.of()).orElseThrow();

        assertThat(r.pctIncrease()).isNull();
        assertThat(r.level()).isEqualTo("BURST");
        assertThat(r.note()).contains("no percentage");
    }

    @Test
    void tinyBaselineIsNotTurnedIntoAHugePercentage() {
        // 1 event in 3 windows = baseline 0.33, below minBaseline 1.0
        RisingRepository r = eval(4, 9, Map.of(3 * W, 1L)).orElseThrow();

        assertThat(r.pctIncrease()).isNull();
        assertThat(r.level()).isEqualTo("BURST");
    }

    @Test
    void usesOnlyAvailableWindowsWhenHistoryIsShorterThanBaseline() {
        // first window at 0, current window 2: only 2 complete windows observed
        RisingRepository r = eval(2, 20, Map.of(1 * W, 4L, 0L, 2L)).orElseThrow();

        assertThat(r.baseline()).isEqualTo(3.0);
        assertThat(r.pctIncrease()).isEqualTo(566.7);
    }
}
