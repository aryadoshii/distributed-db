package db.txn;

import db.txn.mvcc.Snapshot;
import db.txn.mvcc.VersionedRow;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Represents a single database transaction.
 * Holds the transaction's snapshot, write set, and lock set.
 */
public final class Transaction {

    public final long         txnId;
    public final Snapshot     snapshot;
    private volatile TxnStatus status;

    /** All VersionedRow nodes written by this transaction. */
    private final List<VersionedRow> writeSet = new ArrayList<>();

    /** Row keys (primary keys) for which this transaction holds write locks. */
    private final Set<Integer> lockSet = new HashSet<>();

    public Transaction(long txnId, Snapshot snapshot) {
        this.txnId    = txnId;
        this.snapshot = snapshot;
        this.status   = TxnStatus.ACTIVE;
    }

    public TxnStatus getStatus()                  { return status; }
    public void      setStatus(TxnStatus status)  { this.status = status; }

    public void addToWriteSet(VersionedRow version) { writeSet.add(version); }
    public List<VersionedRow> getWriteSet()         { return writeSet; }

    public void      addLock(int rowKey)    { lockSet.add(rowKey); }
    public Set<Integer> getLockSet()        { return lockSet; }
}
