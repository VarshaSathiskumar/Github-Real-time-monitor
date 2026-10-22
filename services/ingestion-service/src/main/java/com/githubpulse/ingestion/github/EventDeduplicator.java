package com.githubpulse.ingestion.github;

import com.githubpulse.ingestion.config.IngestionProperties;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Bounded LRU set of GitHub event IDs already published. This is the first line of defence against
 * the overlap between consecutive polls; Kafka Streams and PostgreSQL deduplicate again downstream.
 */
@Component
public class EventDeduplicator {

    private final Set<String> seen;

    @Autowired
    public EventDeduplicator(IngestionProperties props) {
        this(props.dedupCacheSize());
    }

    public EventDeduplicator(int capacity) {
        Map<String, Boolean> lru = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > capacity;
            }
        };
        this.seen = Collections.newSetFromMap(Collections.synchronizedMap(lru));
    }

    public boolean alreadySeen(String id) {
        return id != null && seen.contains(id);
    }

    public void markSeen(String id) {
        if (id != null) {
            seen.add(id);
        }
    }

    public int size() {
        return seen.size();
    }
}
