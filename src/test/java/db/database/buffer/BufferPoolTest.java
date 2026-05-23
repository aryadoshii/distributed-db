package db.storage.buffer;

import db.storage.page.DiskManager;
import db.storage.page.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BufferPoolTest {

    @TempDir
    Path tempDir;

    private Path dbPath;
    private DiskManager diskManager;
    private BufferPool pool;

    @BeforeEach
    void setUp() throws IOException {
        dbPath = tempDir.resolve("test.db");
        diskManager = new DiskManager(dbPath);
        pool = new BufferPool(4, diskManager);
    }

    @AfterEach
    void tearDown() throws IOException {
        diskManager.close();
    }

    // --- newPage ---

    @Test
    void newPageCreatesAPageAndPinsIt() throws IOException {
        Page page = pool.newPage(Page.PageType.DATA);

        assertNotNull(page);
        assertTrue(page.isPinned());
        assertEquals(Page.PageType.DATA, page.getPageType());
        assertEquals(1, pool.size());
    }

    // --- fetchPage ---

    @Test
    void fetchPageReturnsSamePageOnCacheHit() throws IOException {
        Page original = pool.newPage(Page.PageType.DATA);
        int pageId = original.getPageId();

        // Page is still in pool (not evicted) — fetchPage must be a cache hit
        Page fetched = pool.fetchPage(pageId);

        assertSame(original, fetched,
                "fetchPage should return the same Page instance on a cache hit");
    }

    // --- unpinPage ---

    @Test
    void unpinPageMakesPageNoLongerPinned() throws IOException {
        Page page = pool.newPage(Page.PageType.DATA);
        assertTrue(page.isPinned());

        pool.unpinPage(page.getPageId(), false);

        assertFalse(page.isPinned());
    }

    @Test
    void unpinPageWithDirtyFlagMarksDirty() throws IOException {
        Page page = pool.newPage(Page.PageType.DATA);
        page.markClean();  // reset dirty flag so we can test the parameter

        pool.unpinPage(page.getPageId(), true);

        assertTrue(page.isDirty());
    }

    // --- eviction ---

    @Test
    void poolAtCapacityEvictsLRUPageWhenRequestingNewPage() throws IOException {
        pool = new BufferPool(2, diskManager);

        Page page0 = pool.newPage(Page.PageType.DATA);
        Page page1 = pool.newPage(Page.PageType.DATA);

        // Unpin both; page0 is LRU
        pool.unpinPage(page0.getPageId(), false);
        pool.unpinPage(page1.getPageId(), false);

        // Requesting a third page must evict page0 (LRU)
        Page page2 = pool.newPage(Page.PageType.DATA);

        assertNotNull(page2);
        assertEquals(2, pool.size());  // page1 + page2 remain
    }

    @Test
    void evictionWritesDirtyPageToDisk() throws IOException {
        pool = new BufferPool(1, diskManager);

        Page page0 = pool.newPage(Page.PageType.DATA);
        // Write something so dirty flag is set (it already is from construction,
        // but let's be explicit)
        page0.markDirty();

        // Unpin with dirty=true — pool will evict and flush when next page is requested
        pool.unpinPage(page0.getPageId(), true);

        // Request a new page — triggers eviction + disk write of page0
        pool.newPage(Page.PageType.DATA);

        // page0 was dirty and should have been flushed to disk
        assertTrue(Files.size(dbPath) >= Page.PAGE_SIZE,
                "Dirty evicted page must be written to disk");
    }

    @Test
    void illegalStateExceptionWhenPoolFullAndAllPagesPinned() throws IOException {
        pool = new BufferPool(2, diskManager);

        // Fill pool and keep all pages pinned (never unpin)
        pool.newPage(Page.PageType.DATA);
        pool.newPage(Page.PageType.DATA);

        // Pool is full and no page is evictable
        assertThrows(IllegalStateException.class,
                () -> pool.newPage(Page.PageType.DATA));
    }
}
