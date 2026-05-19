package db.txn.mvcc;

import java.util.concurrent.locks.StampedLock;

/**
 * A thread-safe linked list of row versions, newest first.
 *
 * One VersionChain exists per logical row (per primary key value).
 * The chain grows at the head on every write.
 * Reads walk the chain until they find the newest visible version.
 *
 * Null head: a chain created via VersionChainMap.getOrCreate() starts with
 * head=null. getVisible() on an empty chain always returns null.
 */
public class VersionChain {

    private volatile VersionedRow head;
    private final StampedLock     lock = new StampedLock();

    /** Create a chain with an initial version (bootstrap or first write). */
    public VersionChain(VersionedRow initialVersion) {
        this.head = initialVersion;
    }

    /** Create an empty chain (no versions yet). */
    public VersionChain() {
        this(null);
    }

    /**
     * Find the newest version visible to the given snapshot.
     * Returns null if no version is visible (row doesn't exist from this
     * transaction's perspective, or chain is empty, or head is a tombstone).
     */
    public VersionedRow getVisible(Snapshot snapshot) {
        long stamp = lock.tryOptimisticRead();
        VersionedRow current = head;

        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try { current = head; }
            finally { lock.unlockRead(stamp); }
        }

        while (current != null) {
            if (snapshot.isVisible(current.getTxnId(), current.isCommitted())) {
                return current.isDeleted() ? null : current;
            }
            current = current.getPrev();
        }
        return null;
    }

    /**
     * Prepend a new version at the head of the chain.
     * Caller must hold the row write lock before calling this.
     */
    public VersionedRow prepend(long txnId, byte[] value, boolean deleted) {
        long stamp = lock.writeLock();
        try {
            VersionedRow newVersion = new VersionedRow(txnId, value, deleted, head);
            head = newVersion;
            return newVersion;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Returns the txnId of the current uncommitted head writer, or -1 if none.
     * Used for write-write conflict detection.
     */
    public long getUncommittedWriter() {
        long stamp = lock.readLock();
        try {
            if (head != null && !head.isCommitted()) return head.getTxnId();
            return -1L;
        } finally {
            lock.unlockRead(stamp);
        }
    }

    public VersionedRow getHead() { return head; }
}
