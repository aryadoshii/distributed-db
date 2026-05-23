package db.storage.btree;

import db.storage.page.Page;

/**
 * A B-tree leaf node. Stores the actual key-value pairs.
 *
 * On-page layout (after the 16-byte Page header):
 *   [16..19]  num_keys        (int)
 *   [20..23]  next_leaf_id    (int)  — page ID of the next leaf, -1 if last
 *   [24..]    entries:
 *               [key       : 4 bytes]
 *               [value_len : 4 bytes]
 *               [value     : value_len bytes]
 *             repeated num_keys times
 *
 * Leaf nodes are linked via next_leaf_id to form a sorted linked list,
 * enabling O(log n + k) range scans without traversing the tree again.
 */
public class BTreeLeaf extends BTreeNode {

    private static final int OFFSET_NEXT_LEAF = 20;
    private static final int ENTRIES_START    = 24;
    private static final int KEY_SIZE         = 4;
    private static final int VAL_LEN_SIZE     = 4;
    private static final int ENTRY_OVERHEAD   = KEY_SIZE + VAL_LEN_SIZE;

    public BTreeLeaf(Page page) {
        super(page);
        if (!page.getPageType().equals(Page.PageType.BTREE_LEAF)) {
            throw new IllegalArgumentException("Page is not a BTREE_LEAF");
        }
        if (getNumKeys() == 0 && getNextLeafId() == 0) {
            // fresh page — initialize next pointer to -1 (no next leaf)
            setNextLeafId(Page.INVALID_PAGE_ID);
        }
    }

    // --- Search ---

    /**
     * Binary search for a key. Returns the value bytes, or null if not found.
     */
    public byte[] search(int key) {
        int idx = binarySearch(key);
        if (idx < 0) return null;
        return getValue(idx);
    }

    /**
     * Find the index of the first key >= searchKey (for range scan start).
     */
    public int findFirstGe(int searchKey) {
        int lo = 0, hi = getNumKeys() - 1, result = getNumKeys();
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            if (getKey(mid) >= searchKey) { result = mid; hi = mid - 1; }
            else lo = mid + 1;
        }
        return result;
    }

    // --- Insert ---

    /**
     * Insert a key-value pair into this leaf.
     * Caller must ensure the leaf is not full before calling.
     * Keys are kept in sorted order.
     */
    public void insert(int key, byte[] value) {
        int n = getNumKeys();
        // Find insertion position
        int pos = 0;
        while (pos < n && getKey(pos) < key) pos++;

        // Check for duplicate key — overwrite existing value
        if (pos < n && getKey(pos) == key) {
            setEntry(pos, key, value);
            return;
        }

        // Read entries that must shift right, then write left-to-right.
        // Writing right-to-left with variable-length values leaves stale bytes
        // between entries when the new value is shorter than the displaced one,
        // causing entryOffset() to compute wrong positions on the next read.
        int[] savedKeys = new int[n - pos];
        byte[][] savedVals = new byte[n - pos][];
        for (int i = 0; i < n - pos; i++) {
            savedKeys[i] = getKey(pos + i);
            savedVals[i] = getValue(pos + i);
        }

        setEntry(pos, key, value);  // new entry lands at the correct old offset
        for (int i = 0; i < savedKeys.length; i++) {
            // left-to-right: entryOffset(pos+1+i) rescans the already-written
            // entries 0..pos+i and gets the right byte position each time
            setEntry(pos + 1 + i, savedKeys[i], savedVals[i]);
        }
        setNumKeys(n + 1);
    }

    /**
     * Delete a key from this leaf.
     * @return true if found and deleted, false if key not present
     */
    public boolean delete(int key) {
        int idx = binarySearch(key);
        if (idx < 0) return false;

        int n = getNumKeys();
        // Shift entries left
        for (int i = idx; i < n - 1; i++) {
            setEntry(i, getKey(i + 1), getValue(i + 1));
        }
        setNumKeys(n - 1);
        return true;
    }

    // --- Split ---

    /**
     * Split this full leaf into two leaves.
     * The right half of this leaf's entries move to newLeaf.
     * Returns the first key of newLeaf (the separator key pushed to parent).
     *
     * Before: this = [k0, k1, k2, k3, k4]  (ORDER = 4, full)
     * After:  this = [k0, k1]   newLeaf = [k2, k3, k4]
     *         separator key = k2 (smallest key in newLeaf)
     */
    public int splitInto(BTreeLeaf newLeaf) {
        int n = getNumKeys();
        int mid = n / 2;  // right half starts here

        // Copy right half to newLeaf
        for (int i = mid; i < n; i++) {
            newLeaf.insert(getKey(i), getValue(i));
        }

        // Truncate this leaf
        setNumKeys(mid);

        // Fix linked list pointers: this → newLeaf → old next
        newLeaf.setNextLeafId(this.getNextLeafId());
        this.setNextLeafId(newLeaf.getPageId());

        // Separator key is the smallest key in the new right leaf
        return newLeaf.getKey(0);
    }

    // --- Navigation ---

    public int getNextLeafId() {
        return readInt(OFFSET_NEXT_LEAF);
    }

    public void setNextLeafId(int pageId) {
        writeInt(OFFSET_NEXT_LEAF, pageId);
    }

    // --- Entry access ---

    public int getKey(int index) {
        return readInt(entryOffset(index));
    }

    public byte[] getValue(int index) {
        int offset = entryOffset(index) + KEY_SIZE;
        int len = readInt(offset);
        return page.readBytes(offset + VAL_LEN_SIZE, len);
    }

    private void setEntry(int index, int key, byte[] value) {
        int offset = entryOffset(index);
        writeInt(offset, key);
        writeInt(offset + KEY_SIZE, value.length);
        page.writeBytes(offset + ENTRY_OVERHEAD, value);
    }

    /**
     * Calculate the byte offset of entry[index] within the page.
     * Since values can be variable-length, we scan linearly.
     * For fixed-value-size workloads you'd precompute this.
     */
    private int entryOffset(int index) {
        int offset = ENTRIES_START;
        for (int i = 0; i < index; i++) {
            int valLen = readInt(offset + KEY_SIZE);
            offset += ENTRY_OVERHEAD + valLen;
        }
        return offset;
    }

    private int binarySearch(int key) {
        int lo = 0, hi = getNumKeys() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            int midKey = getKey(mid);
            if (midKey == key) return mid;
            else if (midKey < key) lo = mid + 1;
            else hi = mid - 1;
        }
        return -1;
    }
}