package db.storage.btree;

import db.storage.page.Page;

/**
 * Abstract base for B-tree nodes.
 * Each node occupies exactly one Page on disk.
 *
 * Subclasses:
 *   BTreeLeaf     — stores actual key-value pairs, linked to next leaf
 *   BTreeInternal — stores separator keys and child page pointers
 */
public abstract class BTreeNode {

    // Max keys per node. With 4KB pages and 4-byte keys + 4-byte child pointers,
    // we can fit ~340 keys in an internal node. We use 200 as a safe order.
    public static final int ORDER = 200;

    protected final Page page;

    // Header offsets (after the 16-byte Page header)
    protected static final int OFFSET_NUM_KEYS = 16;  // int: number of keys

    protected BTreeNode(Page page) {
        this.page = page;
    }

    public int getPageId() {
        return page.getPageId();
    }

    public int getNumKeys() {
        return readInt(OFFSET_NUM_KEYS);
    }

    protected void setNumKeys(int n) {
        writeInt(OFFSET_NUM_KEYS, n);
    }

    public boolean isLeaf() {
        return page.getPageType() == Page.PageType.BTREE_LEAF;
    }

    public boolean isFull() {
        return getNumKeys() >= ORDER;
    }

    public Page getPage() {
        return page;
    }

    // Convenience wrappers around Page's byte I/O

    protected int readInt(int offset) {
        byte[] bytes = page.readBytes(offset, 4);
        return ((bytes[0] & 0xFF) << 24)
             | ((bytes[1] & 0xFF) << 16)
             | ((bytes[2] & 0xFF) << 8)
             |  (bytes[3] & 0xFF);
    }

    protected void writeInt(int offset, int value) {
        byte[] bytes = new byte[]{
            (byte)(value >> 24),
            (byte)(value >> 16),
            (byte)(value >> 8),
            (byte) value
        };
        page.writeBytes(offset, bytes);
    }

    protected long readLong(int offset) {
        byte[] bytes = page.readBytes(offset, 8);
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result = (result << 8) | (bytes[i] & 0xFF);
        }
        return result;
    }

    protected void writeLong(int offset, long value) {
        byte[] bytes = new byte[8];
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte)(value & 0xFF);
            value >>= 8;
        }
        page.writeBytes(offset, bytes);
    }
}