package db.txn;

import db.txn.lock.DeadlockException;
import db.txn.lock.LockManager;
import db.txn.lock.LockTimeoutException;
import db.txn.mvcc.Snapshot;
import db.txn.mvcc.VersionedRow;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central coordinator for transaction lifecycle and concurrency control.
 *
 * Responsibilities:
 *   - Allocate monotonically increasing transaction IDs
 *   - Capture consistent snapshots for snapshot isolation
 *   - Bind the current transaction to a ThreadLocal for executor access
 *   - Drive commit (mark versions committed, release locks)
 *   - Drive abort (versions remain uncommitted = invisible, release locks)
 */
public final class TransactionManager {

    private final AtomicLong              nextTxnId    = new AtomicLong(1);
    private final Map<Long, Transaction>  activeTxns   = new ConcurrentHashMap<>();
    private final LockManager             lockManager  = new LockManager();
    private final ThreadLocal<Transaction> currentTxn  = new ThreadLocal<>();

    // ---------------------------------------------------------------
    //  Begin / Commit / Abort
    // ---------------------------------------------------------------

    /**
     * Start a new transaction. Binds it to the calling thread.
     */
    public Transaction begin() {
        long txnId;
        Set<Long> activeSnapshot;

        // Snapshot the active set atomically with ID allocation
        synchronized (this) {
            txnId = nextTxnId.getAndIncrement();
            activeSnapshot = new HashSet<>(activeTxns.keySet());
        }

        Snapshot    snapshot = new Snapshot(txnId, activeSnapshot);
        Transaction txn      = new Transaction(txnId, snapshot);
        activeTxns.put(txnId, txn);
        currentTxn.set(txn);
        return txn;
    }

    /**
     * Commit the given transaction:
     *   1. Mark all written versions as committed (makes them visible to future snapshots)
     *   2. Release all write locks
     *   3. Remove from active set
     */
    public void commit(Transaction txn) {
        if (txn.getStatus() != TxnStatus.ACTIVE)
            throw new IllegalStateException("Cannot commit txn " + txn.txnId + ": " + txn.getStatus());

        for (VersionedRow version : txn.getWriteSet()) {
            version.markCommitted();
        }
        txn.setStatus(TxnStatus.COMMITTED);
        finalize(txn);
    }

    /**
     * Abort the given transaction (idempotent):
     *   - Uncommitted versions remain invisible automatically
     *   - Release all write locks
     *   - Remove from active set
     */
    public void abort(Transaction txn) {
        if (txn.getStatus() == TxnStatus.ABORTED) return;
        // Mark aborted (versions stay uncommitted = invisible)
        for (VersionedRow version : txn.getWriteSet()) {
            version.markAborted();
        }
        txn.setStatus(TxnStatus.ABORTED);
        finalize(txn);
    }

    private void finalize(Transaction txn) {
        lockManager.releaseAll(txn.txnId, txn.getLockSet());
        activeTxns.remove(txn.txnId);
        if (currentTxn.get() == txn) currentTxn.remove();
    }

    // ---------------------------------------------------------------
    //  Write lock acquisition (called by executor operators)
    // ---------------------------------------------------------------

    /**
     * Acquire a write lock on a row key for the given transaction.
     * Records the lock in the transaction's lock set.
     */
    public void acquireWriteLock(Transaction txn, int rowKey)
            throws DeadlockException, LockTimeoutException, InterruptedException {
        lockManager.acquireWriteLock(txn.txnId, rowKey);
        txn.addLock(rowKey);
    }

    // ---------------------------------------------------------------
    //  Thread-local access
    // ---------------------------------------------------------------

    /** Returns the transaction bound to the current thread, or null if none. */
    public Transaction currentTransaction() { return currentTxn.get(); }

    /** Bind a transaction to the current thread (e.g., when handing off work). */
    public void bindTransaction(Transaction txn) { currentTxn.set(txn); }

    // ---------------------------------------------------------------
    //  Accessors for testing
    // ---------------------------------------------------------------

    public Map<Long, Transaction> getActiveTxns() { return activeTxns; }
    public LockManager getLockManager()            { return lockManager; }
}
