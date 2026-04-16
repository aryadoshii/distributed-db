package db.storage.page;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DiskManagerTest {

    @TempDir
    Path tempDir;

    private DiskManager diskManager;

    @BeforeEach
    void setUp() throws IOException {
        diskManager = new DiskManager(tempDir.resolve("test.db"));
    }

    @AfterEach
    void tearDown() throws IOException {
        diskManager.close();
    }

    @Test
    void writePageThenReadPageReturnsIdenticalBytes() throws IOException {
        int pageId = diskManager.allocatePage();
        Page page = new Page(pageId, Page.PageType.DATA);

        // Write a recognizable pattern into the data area
        byte[] payload = new byte[100];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i % 127);
        page.writeBytes(Page.HEADER_SIZE, payload);

        diskManager.writePage(page);

        Page loaded = diskManager.readPage(pageId);
        assertArrayEquals(page.toBytes(), loaded.toBytes());
    }

    @Test
    void allocatePageReturnsIncrementingIds() {
        int first  = diskManager.allocatePage();
        int second = diskManager.allocatePage();
        int third  = diskManager.allocatePage();

        assertEquals(second, first + 1);
        assertEquals(third, second + 1);
    }

    @Test
    void readPageOnInvalidIdThrowsIllegalArgumentException() {
        // Negative ID
        assertThrows(IllegalArgumentException.class, () -> diskManager.readPage(-1));
    }

    @Test
    void readPageOnOutOfBoundsIdThrowsIllegalArgumentException() {
        // No pages have been allocated yet — ID 0 is out of bounds
        assertThrows(IllegalArgumentException.class, () -> diskManager.readPage(0));
    }

    @Test
    void getTotalPagesReflectsAllocations() {
        assertEquals(0, diskManager.getTotalPages());
        diskManager.allocatePage();
        diskManager.allocatePage();
        assertEquals(2, diskManager.getTotalPages());
    }
}
