package db.storage.page;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles all raw disk I/O for the database.
 *
 * The database file is a flat sequence of fixed-size pages:
 *   [Page 0][Page 1][Page 2]...[Page N]
 *
 * Page N lives at byte offset: N * PAGE_SIZE
 *
 * Uses Java NIO FileChannel for direct, non-buffered I/O.
 * Thread-safe: multiple threads may read/write different pages concurrently.
 */
public class DiskManager implements AutoCloseable {

    private final FileChannel fileChannel;
    private final AtomicInteger nextPageId;  // monotonically increasing page ID allocator

    public DiskManager(Path dbFilePath) throws IOException {
        this.fileChannel = FileChannel.open(
            dbFilePath,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        );
        // Calculate how many pages already exist in the file
        long existingSize = fileChannel.size();
        int existingPages = (int) (existingSize / Page.PAGE_SIZE);
        this.nextPageId = new AtomicInteger(existingPages);
    }

    /**
     * Allocate a new page ID. Does NOT write anything to disk yet —
     * the buffer pool will write the page when it is evicted or flushed.
     */
    public int allocatePage() {
        return nextPageId.getAndIncrement();
    }

    /**
     * Write a page to disk at its designated offset.
     * This is a synchronous, blocking write.
     *
     * @param page the Page to flush
     * @throws IOException if the write fails
     */
    public void writePage(Page page) throws IOException {
        int pageId = page.getPageId();
        if (pageId < 0) {
            throw new IllegalArgumentException("Cannot write page with invalid ID");
        }
        long offset = (long) pageId * Page.PAGE_SIZE;
        ByteBuffer buffer = ByteBuffer.wrap(page.toBytes());

        // FileChannel.write() may not write all bytes in one call
        // Loop until all PAGE_SIZE bytes are written
        int bytesWritten = 0;
        while (bytesWritten < Page.PAGE_SIZE) {
            bytesWritten += fileChannel.write(buffer, offset + bytesWritten);
        }
        page.markClean();
    }

    /**
     * Read a page from disk by its page ID.
     *
     * @param pageId the page to read
     * @return a Page object loaded with the data from disk
     * @throws IOException if the read fails or the page doesn't exist
     */
    public Page readPage(int pageId) throws IOException {
        if (pageId < 0 || pageId >= nextPageId.get()) {
            throw new IllegalArgumentException("Invalid page ID: " + pageId);
        }
        long offset = (long) pageId * Page.PAGE_SIZE;
        byte[] rawBytes = new byte[Page.PAGE_SIZE];
        ByteBuffer buffer = ByteBuffer.wrap(rawBytes);

        int bytesRead = 0;
        while (bytesRead < Page.PAGE_SIZE) {
            int n = fileChannel.read(buffer, offset + bytesRead);
            if (n == -1) {
                throw new IOException("Unexpected EOF at page " + pageId);
            }
            bytesRead += n;
        }
        return new Page(rawBytes);
    }

    /**
     * Force all pending writes to reach physical storage.
     * Call this before acknowledging a committed transaction.
     */
    public void flush() throws IOException {
        fileChannel.force(true);  // true = also flush file metadata
    }

    public int getTotalPages() {
        return nextPageId.get();
    }

    @Override
    public void close() throws IOException {
        flush();
        fileChannel.close();
    }
}