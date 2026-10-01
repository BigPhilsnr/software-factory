package dev.softwarefactory.operator.web;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Thread-safe, access-ordered map that evicts its least recently used entry beyond a fixed capacity. */
final class LruMap {
    static final int DEFAULT_CAPACITY = 128;

    private LruMap() {}

    static <K, V> Map<K, V> create(int capacity) {
        return Collections.synchronizedMap(new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > capacity;
            }
        });
    }
}
