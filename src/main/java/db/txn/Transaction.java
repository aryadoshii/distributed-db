package db.txn;

import db.txn.lock.DeadlockException;
import db.txn.lock.LockTimeoutException;
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

    private final long               txnId;
    private final Snapshot           snapshot;
    private volatile TxnStatus       status;
    private final TransactionManager txnManager;

    private final List<VersionedRow> writeSet = new ArrayList<>();
    private final Set<Integer>       lockSet  = new HashSet<>();

    public Transaction(long txnId, Snapshot snapshot, TransactionManager txnManager) {
        this.txnId      = txnId;
        this.snapshot   = snapshot;
        this.status     = TxnStatus.ACTIVE;
        this.txnManager = txnManager;
    }

    public long       getTxnId()   { return txnId; }
    public Snapshot   getSnapshot() { return snapshot; }
    public TxnStatus  getStatus()  { return status; }
    public boolean    isActive()   { return status == TxnStatus.ACTIVE; }

    public void setStatus(TxnStatus status) { this.status = status; }

    public void             addToWriteSet(VersionedRow version) { writeSet.add(version); }
    public List<VersionedRow> getWriteSet()                     { return writeSet; }

    public void         addLock(int rowKey) { lockSet.add(rowKey); }
    public Set<Integer> getLockSet()        { return lockSet; }

    /**
     * Acquire a write lock on {@code rowKey} for this transaction.
     * Convenience method so operators don't need a TransactionManager reference.
     */
    public void acquireWriteLock(int rowKey)
            throws DeadlockException, LockTimeoutException {
        try {
            txnManager.acquireWriteLock(this, rowKey);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Lock acquisition interrupted", e);
        }
    }
}
