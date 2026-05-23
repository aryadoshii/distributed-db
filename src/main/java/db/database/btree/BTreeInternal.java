package db.storage.btree;

import db.storage.page.Page;

/**
 * A B-tree internal (non-leaf) node.
 * Stores separator keys and child page pointers.
 *
 * On-page layout (after 16-byte Page header):
 *   [16..19]  num_keys                 (int)
 *   [20..]    child_id(4) | key(4) | child_id(4) | key(4) | ... | child_id(4)
 *             For n keys: n+1 child pointers, interleaved.
 *
 * Invariant: child[i] contains all keys < separator[i]
 *            child[n] contains all keys >= separator[n-1]
 */
public class BTreeInternal extends BTreeNode {

    private static final int ENTRIES_START = 20;
    private static final int SLOT_SIZE     = 8;  // 4 bytes child_id + 4 bytes key

    public BTreeInternal(Page page) {
        super(page);
        if (!page.getPageType().equals(Page.PageType.BTREE_INTERNAL)) {
            throw new IllegalArgumentException("Page is not a BTREE_INTERNAL");
        }
    }

    /**
     * Initialize a brand new root with two children and one separator key.
     * Called right after the root splits.
     */
    public void initRoot(int leftChildId, int separatorKey, int rightChildId) {
        setChildId(0, leftChildId);
        setKey(0, separatorKey);
        setChildId(1, rightChildId);
        setNumKeys(1);
    }

    /**
     * Find which child page to follow for a given search key.
     * Returns the child page ID.
     */
    public int findChild(int searchKey) {
        int n = getNumKeys();
        for (int i = 0; i < n; i++) {
            if (searchKey < getKey(i)) {
                return getChildId(i);
            }
        }
        return getChildId(n);  // rightmost child
    }

    /**
     * Find the index of the child pointer for a given child page ID.
     * Used during splits to know where to insert the new separator key.
     */
    public int findChildIndex(int childPageId) {
        for (int i = 0; i <= getNumKeys(); i++) {
            if (getChildId(i) == childPageId) return i;
        }
        return -1;
    }

    /**
     * Insert a separator key and a new right child after a leaf split.
     * Caller must ensure this node is not full.
     *
     * @param separatorKey  the middle key from the split
     * @param newChildId    the page ID of the new right child
     * @param leftChildId   the page ID of the existing left child
     *                      (we insert the separator to the right of leftChildId)
     */
    public void insertSeparator(int leftChildId, int separatorKey, int newChildId) {
        int pos = findChildIndex(leftChildId);
        if (pos < 0) throw new IllegalStateException(
            "Left child " + leftChildId + " not found in internal node " + getPageId()
        );

        int n = getNumKeys();
        // Shift everything from pos rightward to make room
        for (int i = n; i > pos; i--) {
            setKey(i, getKey(i - 1));
            setChildId(i + 1, getChildId(i));
        }
        setKey(pos, separatorKey);
        setChildId(pos + 1, newChildId);
        setNumKeys(n + 1);
    }

    /**
     * Split this full internal node.
     * Middle key is pushed up to the parent (not stored in either child).
     * Returns the separator key that should go into the parent.
     *
     * Before: [c0|k0|c1|k1|c2|k2|c3|k3|c4]  (4 keys, full)
     * After:  this=[c0|k0|c1]  pushed=k1  newRight=[c2|k2|c3|k3|c4]
     */
    public int splitInto(BTreeInternal newRight) {
        int n = getNumKeys();
        int mid = n / 2;
        int separatorKey = getKey(mid);  // this key is pushed up, not kept

        // Copy right half to newRight (skip the separator key itself)
        newRight.setChildId(0, getChildId(mid + 1));
        int newIdx = 0;
        for (int i = mid + 1; i < n; i++) {
            newRight.setKey(newIdx, getKey(i));
            newRight.setChildId(newIdx + 1, getChildId(i + 1));
            newIdx++;
        }
        newRight.setNumKeys(n - mid - 1);

        // Truncate this node (drop the separator key and right half)
        setNumKeys(mid);

        return separatorKey;
    }

    // --- Slot accessors ---

    public int getKey(int index) {
        // Layout: child(4) key(4) child(4) key(4) ... child(4)
        // key[i] is at: ENTRIES_START + 4 + i * SLOT_SIZE
        return readInt(ENTRIES_START + 4 + index * SLOT_SIZE);
    }

    public void setKey(int index, int key) {
        writeInt(ENTRIES_START + 4 + index * SLOT_SIZE, key);
    }

    public int getChildId(int index) {
        // child[i] is at: ENTRIES_START + i * SLOT_SIZE
        return readInt(ENTRIES_START + index * SLOT_SIZE);
    }

    public void setChildId(int index, int childId) {
        writeInt(ENTRIES_START + index * SLOT_SIZE, childId);
    }
}