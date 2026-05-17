package db.txn.mvcc;

import java.util.concurrent.locks.StampedLock;

/**
 * A per-row version chain protected by a StampedLock.
 * Readers use optimistic reads; writers take a full write lock.
 */
public final class VersionChain {

    private final StampedLock lock = new StampedLock();
    private volatile VersionedRow head = null;  // newest version

    /**
     * Return the newest version visible to the given snapshot, or null if none.
     * Uses optimistic read; falls back to pessimistic read on conflict.
     */
    public VersionedRow getVisible(Snapshot snapshot) {
        long stamp = lock.tryOptimisticRead();
        VersionedRow result = findVisible(head, snapshot);
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                result = findVisible(head, snapshot);
            } finally {
                lock.unlockRead(stamp);
            }
        }
        return result;
    }

    private VersionedRow findVisible(VersionedRow node, Snapshot snapshot) {
        while (node != null) {
            if (snapshot.isVisible(node.txnId, node.committed)) return node;
            node = node.prev;
        }
        return null;
    }

    /**
     * Prepend a new version (under write lock). Called on insert/update/delete.
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
     * Returns the txnId of the current uncommitted writer, or -1 if none.
     * Used for write-write conflict detection.
     */
    public long getUncommittedWriter() {
        long stamp = lock.readLock();
        try {
            if (head != null && !head.committed) return head.txnId;
            return -1L;
        } finally {
            lock.unlockRead(stamp);
        }
    }
}
