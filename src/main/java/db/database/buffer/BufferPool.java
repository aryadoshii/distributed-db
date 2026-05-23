package db.storage.buffer;

import db.storage.page.DiskManager;
import db.storage.page.Page;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * An in-memory cache of Pages. All database operations go through the buffer
 * pool — never directly to DiskManager.
 *
 * Responsibilities:
 *   1. Maintain a fixed-size pool of Page frames in memory
 *   2. Serve page fetch requests (from cache if available, else disk)
 *   3. Evict pages using LRU when the pool is full
 *   4. Track dirty pages and flush them to disk when evicted or on checkpoint
 *
 * Thread safety: a single ReentrantReadWriteLock guards the frame table.
 * Individual pages are pinned before use to prevent eviction mid-operation.
 */
public class BufferPool {

    private final int capacity;                       // max pages in memory
    private final DiskManager diskManager;
    private final Map<Integer, Page> frameTable;      // pageId → Page
    private final LRUReplacer replacer;               // tracks eviction order
    private final ReentrantReadWriteLock lock;

    public BufferPool(int capacity, DiskManager diskManager) {
        this.capacity = capacity;
        this.diskManager = diskManager;
        this.frameTable = new HashMap<>(capacity);
        this.replacer = new LRUReplacer(capacity);
        this.lock = new ReentrantReadWriteLock();
    }

    /**
     * Fetch a page. Returns from cache if present, else reads from disk.
     * The returned page is PINNED — caller MUST call unpin() when done.
     *
     * @param pageId the page to fetch
     * @return the Page, pinned
     * @throws IOException if disk read fails
     * @throws IllegalStateException if the buffer pool is full with all pages pinned
     */
    public Page fetchPage(int pageId) throws IOException {
        lock.writeLock().lock();
        try {
            // Cache hit
            if (frameTable.containsKey(pageId)) {
                Page page = frameTable.get(pageId);
                page.pin();
                replacer.recordAccess(pageId);
                return page;
            }

            // Cache miss — need to load from disk
            if (frameTable.size() >= capacity) {
                evict(); // throws if nothing can be evicted
            }

            Page page = diskManager.readPage(pageId);
            page.pin();
            frameTable.put(pageId, page);
            replacer.recordAccess(pageId);
            return page;

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Create a brand new page, add it to the buffer pool, and return it pinned.
     * Caller must call unpin() when done.
     *
     * @param type the type of the new page
     * @return the new Page, pinned
     * @throws IOException if eviction write fails
     */
    public Page newPage(Page.PageType type) throws IOException {
        lock.writeLock().lock();
        try {
            if (frameTable.size() >= capacity) {
                evict();
            }
            int pageId = diskManager.allocatePage();
            Page page = new Page(pageId, type);
            page.pin();
            frameTable.put(pageId, page);
            replacer.recordAccess(pageId);
            return page;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Unpin a page after use. If dirty=true, marks it as modified.
     * An unpinned page becomes eligible for eviction.
     *
     * @param pageId the page to unpin
     * @param dirty  true if the caller modified the page
     */
    public void unpinPage(int pageId, boolean dirty) {
        lock.writeLock().lock();
        try {
            Page page = frameTable.get(pageId);
            if (page == null) return;
            page.unpin();
            if (dirty) page.markDirty();
            if (!page.isPinned()) {
                replacer.makeEvictable(pageId);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Force a specific page to disk immediately.
     * Used by the WAL before writing log records.
     */
    public void flushPage(int pageId) throws IOException {
        lock.readLock().lock();
        try {
            Page page = frameTable.get(pageId);
            if (page != null && page.isDirty()) {
                diskManager.writePage(page);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Flush ALL dirty pages to disk. Called at checkpoint.
     */
    public void flushAll() throws IOException {
        lock.readLock().lock();
        try {
            for (Page page : frameTable.values()) {
                if (page.isDirty()) {
                    diskManager.writePage(page);
                }
            }
            diskManager.flush();
        } finally {
            lock.readLock().unlock();
        }
    }

    // --- Private ---

    /**
     * Evict the LRU unpinned page. Write to disk if dirty.
     * Caller must hold the write lock.
     */
    private void evict() throws IOException {
        int victimId = replacer.evict()
            .orElseThrow(() -> new IllegalStateException(
                "Buffer pool full: all " + capacity + " pages are pinned. " +
                "Check for unpin() calls missing after fetchPage()."
            ));
        Page victim = frameTable.remove(victimId);
        if (victim != null && victim.isDirty()) {
            diskManager.writePage(victim);
        }
    }

    public int size() {
        lock.readLock().lock();
        try { return frameTable.size(); }
        finally { lock.readLock().unlock(); }
    }
}