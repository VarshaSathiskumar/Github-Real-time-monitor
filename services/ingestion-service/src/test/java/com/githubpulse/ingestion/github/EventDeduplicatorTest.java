package com.githubpulse.ingestion.github;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EventDeduplicatorTest {

    @Test
    void remembersMarkedIds() {
        EventDeduplicator d = new EventDeduplicator(10);
        assertThat(d.alreadySeen("1")).isFalse();
        d.markSeen("1");
        assertThat(d.alreadySeen("1")).isTrue();
    }

    @Test
    void evictsLeastRecentlyUsedWhenFull() {
        EventDeduplicator d = new EventDeduplicator(2);
        d.markSeen("1");
        d.markSeen("2");
        d.markSeen("3");
        assertThat(d.size()).isEqualTo(2);
        assertThat(d.alreadySeen("1")).isFalse();
        assertThat(d.alreadySeen("3")).isTrue();
    }

    @Test
    void ignoresNullIds() {
        EventDeduplicator d = new EventDeduplicator(2);
        d.markSeen(null);
        assertThat(d.alreadySeen(null)).isFalse();
        assertThat(d.size()).isZero();
    }
}
