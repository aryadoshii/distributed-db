package db.txn.lock;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central lock manager. Maintains one LockTable per row key.
 * Handles deadlock detection before blocking.
 */
public final class LockManager {

    private static final long LOCK_TIMEOUT_MS = 5_000L;

    private final Map<Integer, LockTable> lockMap = new ConcurrentHashMap<>();

    /**
     * Acquire a write lock on {@code rowKey} for {@code txnId}.
     *
     * @throws DeadlockException    if this txn was chosen as the deadlock victim
     * @throws LockTimeoutException if the lock could not be acquired within the timeout
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public void acquireWriteLock(long txnId, int rowKey)
            throws DeadlockException, LockTimeoutException, InterruptedException {

        LockTable lt = lockMap.computeIfAbsent(rowKey, k -> new LockTable());

        // Check for deadlock before blocking
        long victim = DeadlockDetector.detectAndChooseVictim(lockMap);
        if (victim == txnId) {
            throw new DeadlockException("Txn " + txnId + " chosen as deadlock victim");
        }

        lt.acquire(txnId, LOCK_TIMEOUT_MS);
    }

    /**
     * Release all write locks held by {@code txnId}.
     */
    public void releaseAll(long txnId, Set<Integer> rowKeys) {
        for (int rowKey : rowKeys) {
            LockTable lt = lockMap.get(rowKey);
            if (lt != null) lt.release(txnId);
        }
    }

    public Map<Integer, LockTable> getLockMap() { return lockMap; }
}
