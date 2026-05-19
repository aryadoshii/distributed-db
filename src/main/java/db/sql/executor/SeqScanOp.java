package db.sql.executor;

import db.server.Catalog;
import db.sql.ast.ColumnDef;
import db.storage.btree.BTreeInternal;
import db.storage.btree.BTreeLeaf;
import db.storage.buffer.BufferPool;
import db.storage.page.Page;
import db.txn.Transaction;
import db.txn.mvcc.VersionChain;
import db.txn.mvcc.VersionChainMap;
import db.txn.mvcc.VersionedRow;

import java.io.IOException;
import java.util.List;

/**
 * Sequential scan with MVCC snapshot reads.
 *
 * Walks the B+ tree leaf linked list from left to right. For each key,
 * looks up the VersionChain and applies the transaction's snapshot to find
 * the visible version. Rows with no visible version are silently skipped.
 */
public class SeqScanOp implements Operator {

    private final Catalog.TableInfo  tableInfo;
    private final BufferPool         bufferPool;
    private final int                rootPageId;
    private final TransactionHolder  holder;
    private final VersionChainMap    versionChainMap;

    private BTreeLeaf currentLeaf;
    private int       currentIndex;

    public SeqScanOp(Catalog.TableInfo tableInfo,
                     BufferPool bufferPool,
                     int rootPageId,
                     TransactionHolder holder,
                     VersionChainMap versionChainMap) {
        this.tableInfo       = tableInfo;
        this.bufferPool      = bufferPool;
        this.rootPageId      = rootPageId;
        this.holder          = holder;
        this.versionChainMap = versionChainMap;
    }

    @Override
    public void open() {
        try {
            currentLeaf  = findLeftmostLeaf();
            currentIndex = 0;
        } catch (IOException e) {
            throw new RuntimeException("Failed to open sequential scan", e);
        }
    }

    @Override
    public Row next() {
        Transaction txn = holder.get();
        try {
            while (currentLeaf != null) {
                if (currentIndex < currentLeaf.getNumKeys()) {
                    int    key      = currentLeaf.getKey(currentIndex);
                    byte[] rawBytes = currentLeaf.getValue(currentIndex);
                    currentIndex++;

                    VersionChain chain = versionChainMap.getOrBootstrap(key, rawBytes);
                    VersionedRow visible = chain.getVisible(txn.getSnapshot());

                    if (visible == null) continue;

                    return deserializeRow(key, visible.getValue());
                }

                int nextId = currentLeaf.getNextLeafId();
                bufferPool.unpinPage(currentLeaf.getPageId(), false);

                if (nextId == Page.INVALID_PAGE_ID) {
                    currentLeaf = null;
                    return null;
                }

                currentLeaf  = new BTreeLeaf(bufferPool.fetchPage(nextId));
                currentIndex = 0;
            }
            return null;
        } catch (IOException e) {
            throw new RuntimeException("Error during sequential scan", e);
        }
    }

    @Override
    public void close() {
        if (currentLeaf != null) {
            bufferPool.unpinPage(currentLeaf.getPageId(), false);
            currentLeaf = null;
        }
    }

    private Row deserializeRow(int pkValue, byte[] bytes) {
        Row row = new Row();
        List<ColumnDef> cols = tableInfo.columns;
        int offset = 0;

        for (ColumnDef col : cols) {
            if (col.primaryKey()) { row.put(col.name(), pkValue); continue; }

            boolean isNull = bytes[offset++] == 1;
            if (isNull) { row.put(col.name(), null); offset++; continue; }

            byte typeTag = bytes[offset++];
            switch (typeTag) {
                case 0 -> {
                    int val = ((bytes[offset]   & 0xFF) << 24)
                            | ((bytes[offset+1] & 0xFF) << 16)
                            | ((bytes[offset+2] & 0xFF) << 8)
                            |  (bytes[offset+3] & 0xFF);
                    row.put(col.name(), val);
                    offset += 4;
                }
                case 1 -> {
                    int len = ((bytes[offset]   & 0xFF) << 24)
                            | ((bytes[offset+1] & 0xFF) << 16)
                            | ((bytes[offset+2] & 0xFF) << 8)
                            |  (bytes[offset+3] & 0xFF);
                    offset += 4;
                    row.put(col.name(), new String(bytes, offset, len));
                    offset += len;
                }
                case 2 -> row.put(col.name(), bytes[offset++] == 1);
            }
        }
        return row;
    }

    private BTreeLeaf findLeftmostLeaf() throws IOException {
        int pageId = rootPageId;
        while (true) {
            Page page = bufferPool.fetchPage(pageId);
            if (page.getPageType() == Page.PageType.BTREE_LEAF) return new BTreeLeaf(page);
            BTreeInternal internal = new BTreeInternal(page);
            int childId = internal.getChildId(0);
            bufferPool.unpinPage(pageId, false);
            pageId = childId;
        }
    }
}
