package db.txn.lock;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-row lock. Supports one writer at a time.
 * Waiters block on a Condition and are queued for fairness.
 */
public final class LockTable {

    private final ReentrantLock mutex    = new ReentrantLock();
    private final Condition     released = mutex.newCondition();

    private long           holderTxnId = -1L;
    private final Queue<Long> waiters  = new ArrayDeque<>();

    /**
     * Acquire the lock for {@code txnId}, waiting up to {@code timeoutMs} ms.
     *
     * @throws LockTimeoutException if the timeout expires before the lock is acquired
     */
    public void acquire(long txnId, long timeoutMs) throws LockTimeoutException, InterruptedException {
        mutex.lock();
        try {
            if (holderTxnId == txnId) return;  // already held by us (re-entrant for same txn)

            waiters.add(txnId);
            long deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L;

            while (holderTxnId != -1L) {
                long remainingNs = deadlineNs - System.nanoTime();
                if (remainingNs <= 0) {
                    waiters.remove(txnId);
                    throw new LockTimeoutException("Lock timeout for txn " + txnId);
                }
                released.awaitNanos(remainingNs);
            }

            waiters.remove(txnId);
            holderTxnId = txnId;
        } finally {
            mutex.unlock();
        }
    }

    /**
     * Release the lock held by {@code txnId}. Signals all waiters.
     */
    public void release(long txnId) {
        mutex.lock();
        try {
            if (holderTxnId == txnId) {
                holderTxnId = -1L;
                released.signalAll();
            }
        } finally {
            mutex.unlock();
        }
    }

    public long getHolderTxnId() {
        mutex.lock();
        try { return holderTxnId; } finally { mutex.unlock(); }
    }

    public Queue<Long> getWaiters() {
        mutex.lock();
        try { return new ArrayDeque<>(waiters); } finally { mutex.unlock(); }
    }
}
