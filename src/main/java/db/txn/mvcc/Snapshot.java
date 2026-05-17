package db.txn.mvcc;

import java.util.Set;

/**
 * Immutable snapshot of the database state at a point in time.
 * Used to determine which row versions are visible to a transaction.
 */
public final class Snapshot {

    public final long      txnId;       // ID of the transaction that owns this snapshot
    public final Set<Long> activeTxns;  // txnIds active at snapshot time (immutable copy)

    public Snapshot(long txnId, Set<Long> activeTxns) {
        this.txnId      = txnId;
        this.activeTxns = Set.copyOf(activeTxns);
    }

    /**
     * MVCC visibility rule (snapshot isolation):
     * A version written by writerTxnId is visible to this snapshot iff:
     *   1. writerTxnId == this.txnId        — own writes are always visible
     *   2. writerTxnId < this.txnId         — writer started before us
     *   3. writerTxnId NOT in activeTxns    — writer had committed by snapshot time
     *   4. committed == true                — writer has actually committed
     */
    public boolean isVisible(long writerTxnId, boolean committed) {
        if (writerTxnId == txnId)           return true;   // own write
        if (writerTxnId >= txnId)           return false;  // started after us
        if (activeTxns.contains(writerTxnId)) return false; // still active at snapshot
        return committed;
    }
}
