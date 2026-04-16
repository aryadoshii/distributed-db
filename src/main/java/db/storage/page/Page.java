package db.storage.page;

import java.nio.ByteBuffer;

/**
 * A fixed-size (4KB) unit of storage. All data in the database is read
 * from and written to disk in Page units. A page wraps a ByteBuffer and
 * tracks whether it has been modified (dirty) since it was last flushed.
 *
 * Layout of a page on disk:
 *   [0..3]   - page id (int)
 *   [4..7]   - page type (int)  : DATA=1, BTREE_LEAF=2, BTREE_INTERNAL=3, WAL=4
 *   [8..11]  - free space pointer (int) : offset where free space begins
 *   [12..15] - number of tuples/slots (int)
 *   [16..]   - actual data
 */
public class Page {

    public static final int PAGE_SIZE = 4096;         // 4 KB
    public static final int HEADER_SIZE = 16;         // bytes reserved for header
    public static final int INVALID_PAGE_ID = -1;

    // Header field offsets
    private static final int OFFSET_PAGE_ID    = 0;
    private static final int OFFSET_PAGE_TYPE  = 4;
    private static final int OFFSET_FREE_PTR   = 8;
    private static final int OFFSET_TUPLE_COUNT = 12;

    private final ByteBuffer data;    // the raw 4KB block
    private boolean dirty;            // modified since last flush?
    private int pinCount;             // how many threads are using this page

    public enum PageType {
        DATA(1), BTREE_LEAF(2), BTREE_INTERNAL(3), WAL(4);

        private final int code;
        PageType(int code) { this.code = code; }
        public int code() { return code; }

        public static PageType fromCode(int code) {
            for (PageType t : values()) {
                if (t.code == code) return t;
            }
            throw new IllegalArgumentException("Unknown page type: " + code);
        }
    }

    /** Create a brand new empty page with the given id and type. */
    public Page(int pageId, PageType type) {
        this.data = ByteBuffer.allocate(PAGE_SIZE);
        this.dirty = false;
        this.pinCount = 0;
        setPageId(pageId);
        setPageType(type);
        setFreeSpacePointer(HEADER_SIZE);
        setTupleCount(0);
    }

    /** Wrap existing raw bytes read from disk into a Page object. */
    public Page(byte[] rawBytes) {
        if (rawBytes.length != PAGE_SIZE) {
            throw new IllegalArgumentException(
                "Raw bytes must be exactly PAGE_SIZE (" + PAGE_SIZE + ") bytes"
            );
        }
        this.data = ByteBuffer.wrap(rawBytes);
        this.dirty = false;
        this.pinCount = 0;
    }

    // --- Header accessors ---

    public int getPageId() {
        return data.getInt(OFFSET_PAGE_ID);
    }

    public void setPageId(int pageId) {
        data.putInt(OFFSET_PAGE_ID, pageId);
        dirty = true;
    }

    public PageType getPageType() {
        return PageType.fromCode(data.getInt(OFFSET_PAGE_TYPE));
    }

    public void setPageType(PageType type) {
        data.putInt(OFFSET_PAGE_TYPE, type.code());
        dirty = true;
    }

    public int getFreeSpacePointer() {
        return data.getInt(OFFSET_FREE_PTR);
    }

    public void setFreeSpacePointer(int ptr) {
        data.putInt(OFFSET_FREE_PTR, ptr);
        dirty = true;
    }

    public int getTupleCount() {
        return data.getInt(OFFSET_TUPLE_COUNT);
    }

    public void setTupleCount(int count) {
        data.putInt(OFFSET_TUPLE_COUNT, count);
        dirty = true;
    }

    // --- Data access ---

    /**
     * Write bytes into the page at the given offset.
     * Caller is responsible for bounds checking.
     */
    public void writeBytes(int offset, byte[] bytes) {
        if (offset + bytes.length > PAGE_SIZE) {
            throw new IllegalArgumentException("Write exceeds page boundary");
        }
        data.position(offset);
        data.put(bytes);
        dirty = true;
    }

    /**
     * Read bytes from the page starting at offset.
     */
    public byte[] readBytes(int offset, int length) {
        if (offset + length > PAGE_SIZE) {
            throw new IllegalArgumentException("Read exceeds page boundary");
        }
        byte[] result = new byte[length];
        data.position(offset);
        data.get(result);
        return result;
    }

    /** Return a copy of all 4KB raw bytes, suitable for writing to disk. */
    public byte[] toBytes() {
        return data.array().clone();
    }

    public int getFreeSpace() {
        return PAGE_SIZE - getFreeSpacePointer();
    }

    // --- Pin/unpin (used by BufferPool) ---

    public void pin()   { pinCount++; }
    public void unpin() { if (pinCount > 0) pinCount--; }
    public boolean isPinned() { return pinCount > 0; }

    // --- Dirty flag ---

    public boolean isDirty() { return dirty; }
    public void markClean()  { dirty = false; }
    public void markDirty()  { dirty = true; }
}