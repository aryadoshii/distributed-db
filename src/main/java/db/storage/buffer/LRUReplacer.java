package db.storage.buffer;

import java.util.LinkedHashSet;
import java.util.Optional;

/**
 * Tracks eviction order for the buffer pool using LRU policy.
 * Only pages that have been explicitly made evictable (unpinned)
 * are candidates for eviction.
 *
 * Uses a LinkedHashSet: insertion-ordered, O(1) remove, O(1) add.
 * The oldest access is at the front (iterator order).
 */
public class LRUReplacer {

    // Ordered set of evictable page IDs — front = LRU (oldest)
    private final LinkedHashSet<Integer> evictable;
    private final int capacity;

    public LRUReplacer(int capacity) {
        this.capacity = capacity;
        this.evictable = new LinkedHashSet<>();
    }

    /**
     * Record that a page was accessed (cache hit or new load).
     * Moves it to the back (most recently used position).
     */
    public void recordAccess(int pageId) {
        evictable.remove(pageId);    // remove from current position if present
        // Don't re-add yet — page is pinned immediately after access
        // It gets re-added when unpinPage() calls makeEvictable()
    }

    /**
     * Mark a page as evictable (it has been unpinned).
     * Places it at the MRU end of the order.
     */
    public void makeEvictable(int pageId) {
        evictable.remove(pageId);   // ensure no duplicate
        evictable.add(pageId);      // add to tail (MRU)
    }

    /**
     * Remove a page from eviction tracking entirely
     * (e.g., when it's pinned again or removed from the pool).
     */
    public void remove(int pageId) {
        evictable.remove(pageId);
    }

    /**
     * Choose the LRU evictable page and remove it from tracking.
     *
     * @return the page ID to evict, or empty if nothing is evictable
     */
    public Optional<Integer> evict() {
        if (evictable.isEmpty()) return Optional.empty();
        Integer victim = evictable.iterator().next();  // front = LRU
        evictable.remove(victim);
        return Optional.of(victim);
    }

    public int size() { return evictable.size(); }
}