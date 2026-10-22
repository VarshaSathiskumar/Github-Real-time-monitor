package com.githubpulse.analytics.store;

import com.githubpulse.analytics.model.ActivityCounts;
import com.githubpulse.analytics.model.MinuteCounts;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Live window (up to a few hours) served from Redis minute counters. */
@Component
public class RedisActivityStore implements ActivityStore {

    private static final Logger log = LoggerFactory.getLogger(RedisActivityStore.class);

    private final StringRedisTemplate redis;

    public RedisActivityStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public List<MinuteCounts> minuteCounts(Instant from, Instant to) {
        long first = RedisKeys.epochMinute(from);
        long last = RedisKeys.epochMinute(to.minusSeconds(1));
        List<String> keys = LongStream.rangeClosed(first, last).mapToObj(RedisKeys::minute).toList();
        if (keys.isEmpty()) {
            return List.of();
        }
        List<String> values = redis.opsForValue().multiGet(keys);
        List<MinuteCounts> out = new ArrayList<>();
        for (int i = 0; values != null && i < values.size(); i++) {
            String v = values.get(i);
            if (v != null) {
                out.add(new MinuteCounts(Instant.ofEpochSecond((first + i) * 60), parse(v)));
            }
        }
        return out;
    }

    @Override
    public long activeRepositories(Instant from, Instant to) {
        return countDistinct(RedisKeys.epochMinute(from), RedisKeys.epochMinute(to.minusSeconds(1)));
    }

    @Override
    public List<Long> activeRepositorySeries(Instant from, Instant to, int bucketMinutes) {
        long first = Math.floorDiv(RedisKeys.epochMinute(from), (long) bucketMinutes) * bucketMinutes;
        long last = RedisKeys.epochMinute(to.minusSeconds(1));
        List<Long> out = new ArrayList<>();
        for (long b = first; b <= last; b += bucketMinutes) {
            out.add(countDistinct(b, Math.min(last, b + bucketMinutes - 1)));
        }
        return out;
    }

    private long countDistinct(long firstMinute, long lastMinute) {
        String[] keys = LongStream.rangeClosed(firstMinute, lastMinute)
                .mapToObj(RedisKeys::repoSet)
                .toArray(String[]::new);
        if (keys.length == 0) {
            return 0;
        }
        Long n = redis.opsForHyperLogLog().size(keys);
        return n == null ? 0 : n;
    }

    @Override
    public Map<String, ActivityCounts> repositoryTotals(Instant from, Instant to, int limit) {
        long first = RedisKeys.epochMinute(from);
        long last = RedisKeys.epochMinute(to.minusSeconds(1));
        List<String> keys = LongStream.rangeClosed(first, last).mapToObj(RedisKeys::repoMinute).toList();
        Map<String, ActivityCounts> totals = new HashMap<>();
        for (Map<String, String> hash : pipelinedHashes(keys)) {
            hash.forEach((repo, compact) -> totals.merge(repo, parse(compact), ActivityCounts::plus));
        }
        return totals;
    }

    @Override
    public Map<String, List<MinuteCounts>> repositoryMinutes(Collection<String> repos, Instant from, Instant to) {
        long first = RedisKeys.epochMinute(from);
        long last = RedisKeys.epochMinute(to.minusSeconds(1));
        List<String> keys = LongStream.rangeClosed(first, last).mapToObj(RedisKeys::repoMinute).toList();
        List<Map<String, String>> hashes = pipelinedHashes(keys);
        Map<String, List<MinuteCounts>> out = new HashMap<>();
        repos.forEach(r -> out.put(r, new ArrayList<>()));
        for (int i = 0; i < hashes.size(); i++) {
            Instant minute = Instant.ofEpochSecond((first + i) * 60);
            Map<String, String> hash = hashes.get(i);
            for (String repo : repos) {
                String compact = hash.get(repo);
                if (compact != null) {
                    out.get(repo).add(new MinuteCounts(minute, parse(compact)));
                }
            }
        }
        return out;
    }

    /** HGETALL for many keys in one round trip. Missing keys yield empty maps. */
    private List<Map<String, String>> pipelinedHashes(List<String> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        List<Object> raw = redis.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) conn -> {
            for (String k : keys) {
                conn.hashCommands().hGetAll(k.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
        List<Map<String, String>> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            Map<String, String> m = new HashMap<>();
            if (o instanceof Map<?, ?> map) {
                map.forEach((k, v) -> m.put(str(k), str(v)));
            }
            out.add(m);
        }
        return out;
    }

    private static String str(Object o) {
        return o instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : String.valueOf(o);
    }

    private static ActivityCounts parse(String compact) {
        try {
            return ActivityCounts.fromCompact(compact);
        } catch (RuntimeException e) {
            log.warn("Ignoring unreadable Redis value '{}'", compact);
            return ActivityCounts.ZERO;
        }
    }
}
