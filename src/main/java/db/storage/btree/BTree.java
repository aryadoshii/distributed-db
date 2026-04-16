package db.storage.btree;

import db.storage.buffer.BufferPool;
import db.storage.page.Page;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A B+ tree index backed by the buffer pool.
 *
 * All reads and writes go through the BufferPool — never directly to disk.
 * Every page access must be followed by unpinPage() to release the pin.
 *
 * This is a B+ tree variant:
 *   - All values live in leaf nodes
 *   - Internal nodes store only separator keys
 *   - Leaf nodes are linked for range scans
 */
public class BTree {

    private final BufferPool bufferPool;
    private int rootPageId;

    public BTree(BufferPool bufferPool) throws IOException {
        this.bufferPool = bufferPool;
        // Create the initial empty root (a leaf)
        Page rootPage = bufferPool.newPage(Page.PageType.BTREE_LEAF);
        this.rootPageId = rootPage.getPageId();
        bufferPool.unpinPage(rootPageId, true);
    }

    /** Restore a BTree from an existing root page (used after crash recovery) */
    public BTree(BufferPool bufferPool, int rootPageId) {
        this.bufferPool = bufferPool;
        this.rootPageId = rootPageId;
    }

    // =========================================================
    //  SEARCH
    // =========================================================

    /**
     * Look up a key. Returns the value bytes, or null if not found.
     */
    public byte[] search(int key) throws IOException {
        BTreeLeaf leaf = findLeaf(key);
        try {
            return leaf.search(key);
        } finally {
            bufferPool.unpinPage(leaf.getPageId(), false);
        }
    }

    /**
     * Range scan: return all values for keys in [startKey, endKey] inclusive.
     */
    public List<byte[]> rangeScan(int startKey, int endKey) throws IOException {
        List<byte[]> results = new ArrayList<>();
        BTreeLeaf leaf = findLeaf(startKey);

        outer:
        while (true) {
            int n = leaf.getNumKeys();
            int startIdx = leaf.findFirstGe(startKey);

            for (int i = startIdx; i < n; i++) {
                int k = leaf.getKey(i);
                if (k > endKey) break outer;
                results.add(leaf.getValue(i));
            }

            int nextId = leaf.getNextLeafId();
            bufferPool.unpinPage(leaf.getPageId(), false);

            if (nextId == Page.INVALID_PAGE_ID) break;
            leaf = asLeaf(bufferPool.fetchPage(nextId));
        }
        return results;
    }

    // =========================================================
    //  INSERT
    // =========================================================

    /**
     * Insert a key-value pair.
     * If the key already exists, the value is overwritten.
     */
    public void insert(int key, byte[] value) throws IOException {
        // If root is a leaf and it's full, split it first
        Page rootPage = bufferPool.fetchPage(rootPageId);
        BTreeNode root = loadNode(rootPage);

        if (root.isFull()) {
            bufferPool.unpinPage(rootPageId, false);
            splitRoot();
        } else {
            bufferPool.unpinPage(rootPageId, false);
        }

        insertNonFull(rootPageId, key, value);
    }

    /**
     * Recursive insert into a subtree rooted at pageId.
     * Assumes the node at pageId is NOT full.
     */
    private void insertNonFull(int pageId, int key, byte[] value) throws IOException {
        Page page = bufferPool.fetchPage(pageId);
        BTreeNode node = loadNode(page);

        if (node.isLeaf()) {
            BTreeLeaf leaf = (BTreeLeaf) node;
            leaf.insert(key, value);
            bufferPool.unpinPage(pageId, true);
        } else {
            BTreeInternal internal = (BTreeInternal) node;
            int childId = internal.findChild(key);
            bufferPool.unpinPage(pageId, false);

            // Check if child is full — if so, split it before descending
            Page childPage = bufferPool.fetchPage(childId);
            BTreeNode child = loadNode(childPage);

            if (child.isFull()) {
                bufferPool.unpinPage(childId, false);
                splitChild(pageId, childId);
                // After split, re-determine which child to follow
                Page parentPage = bufferPool.fetchPage(pageId);
                int newChildId = asInternal(parentPage).findChild(key);
                bufferPool.unpinPage(pageId, false);
                insertNonFull(newChildId, key, value);
            } else {
                bufferPool.unpinPage(childId, false);
                insertNonFull(childId, key, value);
            }
        }
    }

    // =========================================================
    //  DELETE
    // =========================================================

    /**
     * Delete a key. Returns true if found and deleted.
     */
    public boolean delete(int key) throws IOException {
        BTreeLeaf leaf = findLeaf(key);
        boolean deleted = leaf.delete(key);
        bufferPool.unpinPage(leaf.getPageId(), deleted);
        return deleted;
    }

    // =========================================================
    //  SPLIT HELPERS
    // =========================================================

    /** Split the root — creates a new internal root with two children. */
    private void splitRoot() throws IOException {
        Page oldRootPage = bufferPool.fetchPage(rootPageId);
        BTreeNode oldRoot = loadNode(oldRootPage);

        // Create new root (internal node)
        Page newRootPage = bufferPool.newPage(Page.PageType.BTREE_INTERNAL);
        BTreeInternal newRoot = asInternal(newRootPage);

        if (oldRoot.isLeaf()) {
            // Split leaf root
            Page newLeafPage = bufferPool.newPage(Page.PageType.BTREE_LEAF);
            BTreeLeaf newLeaf = asLeaf(newLeafPage);
            int separator = ((BTreeLeaf) oldRoot).splitInto(newLeaf);
            newRoot.initRoot(oldRootPage.getPageId(), separator, newLeafPage.getPageId());
            bufferPool.unpinPage(newLeafPage.getPageId(), true);
        } else {
            // Split internal root
            Page newRightPage = bufferPool.newPage(Page.PageType.BTREE_INTERNAL);
            BTreeInternal newRight = asInternal(newRightPage);
            int separator = ((BTreeInternal) oldRoot).splitInto(newRight);
            newRoot.initRoot(oldRootPage.getPageId(), separator, newRightPage.getPageId());
            bufferPool.unpinPage(newRightPage.getPageId(), true);
        }

        bufferPool.unpinPage(oldRootPage.getPageId(), true);
        bufferPool.unpinPage(newRootPage.getPageId(), true);
        rootPageId = newRootPage.getPageId();
    }

    /** Split a full child node and insert the separator into the parent. */
    private void splitChild(int parentPageId, int childPageId) throws IOException {
        Page parentPage = bufferPool.fetchPage(parentPageId);
        Page childPage  = bufferPool.fetchPage(childPageId);
        BTreeInternal parent = asInternal(parentPage);
        BTreeNode child = loadNode(childPage);

        if (child.isLeaf()) {
            Page newLeafPage = bufferPool.newPage(Page.PageType.BTREE_LEAF);
            BTreeLeaf newLeaf = asLeaf(newLeafPage);
            int separator = ((BTreeLeaf) child).splitInto(newLeaf);
            parent.insertSeparator(childPageId, separator, newLeaf.getPageId());
            bufferPool.unpinPage(newLeafPage.getPageId(), true);
        } else {
            Page newInternalPage = bufferPool.newPage(Page.PageType.BTREE_INTERNAL);
            BTreeInternal newInternal = asInternal(newInternalPage);
            int separator = ((BTreeInternal) child).splitInto(newInternal);
            parent.insertSeparator(childPageId, separator, newInternal.getPageId());
            bufferPool.unpinPage(newInternalPage.getPageId(), true);
        }

        bufferPool.unpinPage(parentPageId, true);
        bufferPool.unpinPage(childPageId,  true);
    }

    // =========================================================
    //  TRAVERSAL
    // =========================================================

    /** Traverse from root to the leaf that should contain this key. */
    private BTreeLeaf findLeaf(int key) throws IOException {
        int pageId = rootPageId;
        while (true) {
            Page page = bufferPool.fetchPage(pageId);
            BTreeNode node = loadNode(page);
            if (node.isLeaf()) {
                return (BTreeLeaf) node;  // still pinned — caller unpins
            }
            BTreeInternal internal = (BTreeInternal) node;
            int childId = internal.findChild(key);
            bufferPool.unpinPage(pageId, false);
            pageId = childId;
        }
    }

    // =========================================================
    //  HELPERS
    // =========================================================

    private BTreeNode loadNode(Page page) {
        return page.getPageType() == Page.PageType.BTREE_LEAF
            ? new BTreeLeaf(page)
            : new BTreeInternal(page);
    }

    private BTreeLeaf     asLeaf(Page page)     { return new BTreeLeaf(page); }
    private BTreeInternal asInternal(Page page) { return new BTreeInternal(page); }

    public int getRootPageId() { return rootPageId; }
}