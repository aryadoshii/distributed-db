package db.storage.buffer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LRUReplacerTest {

    private LRUReplacer replacer;

    @BeforeEach
    void setUp() {
        replacer = new LRUReplacer(10);
    }

    @Test
    void evictReturnsEmptyWhenNothingEvictable() {
        assertEquals(Optional.empty(), replacer.evict());
    }

    @Test
    void makeEvictableAddsPageToEvictionCandidates() {
        replacer.makeEvictable(1);
        assertEquals(1, replacer.size());
        assertEquals(Optional.of(1), replacer.evict());
    }

    @Test
    void evictReturnsLRUPage() {
        // Pages made evictable in order: 1, 2, 3 — page 1 is LRU
        replacer.makeEvictable(1);
        replacer.makeEvictable(2);
        replacer.makeEvictable(3);

        assertEquals(Optional.of(1), replacer.evict());
        // After evicting 1, next LRU is 2
        assertEquals(Optional.of(2), replacer.evict());
        assertEquals(Optional.of(3), replacer.evict());
        assertEquals(Optional.empty(), replacer.evict());
    }

    @Test
    void recordAccessRemovesPageFromEvictableSet() {
        // Simulate: page was evictable, then gets accessed (pinned again)
        replacer.makeEvictable(5);
        assertEquals(1, replacer.size());

        replacer.recordAccess(5);
        assertEquals(0, replacer.size());
        assertEquals(Optional.empty(), replacer.evict());
    }

    @Test
    void makeEvictableAfterRecordAccessPutsPageAtMRUEnd() {
        replacer.makeEvictable(1);
        replacer.makeEvictable(2);

        // Access page 1 (simulates re-pin), then unpin again
        replacer.recordAccess(1);
        replacer.makeEvictable(1);

        // Page 2 is now LRU (1 was re-accessed and re-added at MRU end)
        assertEquals(Optional.of(2), replacer.evict());
        assertEquals(Optional.of(1), replacer.evict());
    }
}
