package com.githubpulse.analytics.store;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Tiny time-based memoiser for expensive PostgreSQL window queries. */
public class TtlCache {

    private record Entry(Object value, long expiresAtNanos) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final long ttlNanos;

    public TtlCache(Duration ttl) {
        this.ttlNanos = ttl.toNanos();
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Supplier<T> loader) {
        long now = System.nanoTime();
        Entry e = entries.get(key);
        if (e != null && e.expiresAtNanos() > now) {
            return (T) e.value();
        }
        if (entries.size() > 512) {
            entries.entrySet().removeIf(en -> en.getValue().expiresAtNanos() <= now);
        }
        T value = loader.get();
        entries.put(key, new Entry(value, now + ttlNanos));
        return value;
    }
}
