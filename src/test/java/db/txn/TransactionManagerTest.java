package db.txn;

import db.txn.lock.DeadlockDetector;
import db.txn.lock.LockManager;
import db.txn.lock.LockTable;
import db.txn.mvcc.VersionChain;
import db.txn.mvcc.VersionedRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class TransactionManagerTest {

    private TransactionManager tm;

    @BeforeEach
    void setUp() {
        tm = new TransactionManager();
    }

    // ---------------------------------------------------------------
    //  Snapshot visibility
    // ---------------------------------------------------------------

    @Test
    void committedVersionIsVisibleToLaterSnapshot() {
        // txn 1 writes and commits, txn 2 starts after
        Transaction txn1 = tm.begin();
        VersionChain chain = new VersionChain();
        VersionedRow v = chain.prepend(txn1.txnId, new byte[]{42}, false);
        txn1.addToWriteSet(v);
        tm.commit(txn1);

        Transaction txn2 = tm.begin();
        VersionedRow visible = chain.getVisible(txn2.snapshot);
        tm.abort(txn2);

        assertNotNull(visible);
        assertEquals(42, visible.value[0]);
    }

    @Test
    void uncommittedVersionIsNotVisibleToConcurrentSnapshot() {
        Transaction txn1 = tm.begin();
        Transaction txn2 = tm.begin();  // snapshot taken while txn1 is active

        VersionChain chain = new VersionChain();
        chain.prepend(txn1.txnId, new byte[]{7}, false);

        // txn1 has NOT committed — must be invisible to txn2
        VersionedRow visible = chain.getVisible(txn2.snapshot);
        tm.abort(txn1);
        tm.abort(txn2);

        assertNull(visible);
    }

    @Test
    void futureVersionIsNotVisibleToEarlierSnapshot() {
        Transaction txn1 = tm.begin();
        // txn2 starts AFTER txn1, so txn2.txnId > txn1.txnId
        Transaction txn2 = tm.begin();

        VersionChain chain = new VersionChain();
        VersionedRow v = chain.prepend(txn2.txnId, new byte[]{99}, false);
        txn2.addToWriteSet(v);
        tm.commit(txn2);

        // txn1's snapshot was taken before txn2 existed → must not see txn2's write
        VersionedRow visible = chain.getVisible(txn1.snapshot);
        tm.abort(txn1);

        assertNull(visible);
    }

    @Test
    void ownWriteIsAlwaysVisible() {
        Transaction txn = tm.begin();
        VersionChain chain = new VersionChain();
        // Version not yet committed
        chain.prepend(txn.txnId, new byte[]{55}, false);

        VersionedRow visible = chain.getVisible(txn.snapshot);
        tm.abort(txn);

        assertNotNull(visible);
        assertEquals(55, visible.value[0]);
    }

    @Test
    void deletedTombstoneVersionReturnsNonNullButDeletedTrue() {
        Transaction txn1 = tm.begin();
        VersionChain chain = new VersionChain();
        VersionedRow tomb = chain.prepend(txn1.txnId, null, true);
        txn1.addToWriteSet(tomb);
        tm.commit(txn1);

        Transaction txn2 = tm.begin();
        VersionedRow visible = chain.getVisible(txn2.snapshot);
        tm.abort(txn2);

        // getVisible returns the tombstone node; caller checks .deleted
        assertNotNull(visible);
        assertTrue(visible.deleted);
        assertNull(visible.value);
    }

    // ---------------------------------------------------------------
    //  TransactionManager lifecycle
    // ---------------------------------------------------------------

    @Test
    void beginIncrementsTxnIdMonotonically() {
        Transaction t1 = tm.begin();
        Transaction t2 = tm.begin();
        Transaction t3 = tm.begin();
        tm.abort(t1);
        tm.abort(t2);
        tm.abort(t3);

        assertTrue(t1.txnId < t2.txnId);
        assertTrue(t2.txnId < t3.txnId);
    }

    @Test
    void beginCapturesActiveTxnsInSnapshot() {
        Transaction t1 = tm.begin();
        Transaction t2 = tm.begin();  // t1 is active when t2 starts

        // t2's snapshot must contain t1 as active
        assertTrue(t2.snapshot.activeTxns.contains(t1.txnId));
        // t2 does not include itself in activeTxns (captured before its own entry)
        assertFalse(t2.snapshot.activeTxns.contains(t2.txnId));

        tm.abort(t1);
        tm.abort(t2);
    }

    @Test
    void commitMarksVersionsAsCommitted() {
        Transaction txn = tm.begin();
        VersionChain chain = new VersionChain();
        VersionedRow v = chain.prepend(txn.txnId, new byte[]{1}, false);
        txn.addToWriteSet(v);

        assertFalse(v.isCommitted());
        tm.commit(txn);
        assertTrue(v.isCommitted());
    }

    @Test
    void abortLeavesVersionsUncommitted() {
        Transaction txn = tm.begin();
        VersionChain chain = new VersionChain();
        VersionedRow v = chain.prepend(txn.txnId, new byte[]{2}, false);
        txn.addToWriteSet(v);

        tm.abort(txn);

        assertFalse(v.isCommitted());
        assertEquals(TxnStatus.ABORTED, txn.getStatus());
    }

    @Test
    void activeCountDecrementsOnCommit() {
        Transaction t1 = tm.begin();
        Transaction t2 = tm.begin();
        assertEquals(2, tm.getActiveTxns().size());

        tm.commit(t1);
        assertEquals(1, tm.getActiveTxns().size());

        tm.commit(t2);
        assertEquals(0, tm.getActiveTxns().size());
    }

    @Test
    void activeCountDecrementsOnAbort() {
        Transaction t1 = tm.begin();
        Transaction t2 = tm.begin();
        assertEquals(2, tm.getActiveTxns().size());

        tm.abort(t1);
        assertEquals(1, tm.getActiveTxns().size());

        tm.abort(t2);
        assertEquals(0, tm.getActiveTxns().size());
    }

    @Test
    void currentTransactionReturnsThreadBoundTxn() {
        assertNull(tm.currentTransaction());

        Transaction txn = tm.begin();
        assertSame(txn, tm.currentTransaction());

        tm.commit(txn);
        assertNull(tm.currentTransaction());
    }

    // ---------------------------------------------------------------
    //  LockManager
    // ---------------------------------------------------------------

    @Test
    void acquireWriteLockSucceedsWhenRowIsFree() throws Exception {
        Transaction txn = tm.begin();
        assertDoesNotThrow(() -> tm.acquireWriteLock(txn, 42));
        tm.commit(txn);
    }

    @Test
    void acquireWriteLockBlocksThenSucceedsAfterHolderReleases() throws Exception {
        LockManager lm = new LockManager();
        long holder = 1L;
        long waiter = 2L;

        // holder acquires
        lm.acquireWriteLock(holder, 100);

        CountDownLatch waiterStarted  = new CountDownLatch(1);
        CountDownLatch waiterAcquired = new CountDownLatch(1);

        Thread waiterThread = new Thread(() -> {
            try {
                waiterStarted.countDown();
                lm.acquireWriteLock(waiter, 100);
                waiterAcquired.countDown();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        waiterThread.start();
        waiterStarted.await();

        // Give waiter time to block on the lock
        Thread.sleep(50);
        assertFalse(waiterAcquired.getCount() == 0, "Waiter should still be blocked");

        // Release holder's lock
        lm.releaseAll(holder, Set.of(100));

        // Waiter should now acquire
        waiterAcquired.await();
        waiterThread.join();

        // Clean up
        lm.releaseAll(waiter, Set.of(100));
    }

    @Test
    void releaseAllReleasesAllLocksHeldByTransaction() throws Exception {
        LockManager lm = new LockManager();
        long txnId = 10L;

        lm.acquireWriteLock(txnId, 1);
        lm.acquireWriteLock(txnId, 2);
        lm.acquireWriteLock(txnId, 3);

        lm.releaseAll(txnId, Set.of(1, 2, 3));

        // Another txn should now acquire all three immediately
        long other = 11L;
        assertDoesNotThrow(() -> lm.acquireWriteLock(other, 1));
        assertDoesNotThrow(() -> lm.acquireWriteLock(other, 2));
        assertDoesNotThrow(() -> lm.acquireWriteLock(other, 3));
        lm.releaseAll(other, Set.of(1, 2, 3));
    }

    // ---------------------------------------------------------------
    //  DeadlockDetector
    // ---------------------------------------------------------------

    @Test
    void noDeadlockReturnsMinusOne() {
        // txn 1 holds row 0, txn 2 waits for row 0 — no cycle (1 doesn't wait for 2)
        Map<Integer, LockTable> lockMap = new ConcurrentHashMap<>();
        LockTable lt = new LockTable();
        try { lt.acquire(1L, 100); } catch (Exception ignored) {}
        lockMap.put(0, lt);

        long victim = DeadlockDetector.detectAndChooseVictim(lockMap);
        assertEquals(-1L, victim);
    }

    @Test
    void simpleCycleABDetectedReturnsHighestTxnId() throws Exception {
        // Build wait-for: txn 2 waits for row 0 (held by txn 1)
        //                 txn 1 waits for row 1 (held by txn 2)
        Map<Integer, LockTable> lockMap = new ConcurrentHashMap<>();

        LockTable lt0 = new LockTable();
        lt0.acquire(1L, 1000);  // txn 1 holds row 0
        lockMap.put(0, lt0);

        LockTable lt1 = new LockTable();
        lt1.acquire(2L, 1000);  // txn 2 holds row 1
        lockMap.put(1, lt1);

        // Simulate txn 2 waiting for row 0 and txn 1 waiting for row 1
        // by starting threads that block and then checking the detector
        CountDownLatch bothWaiting = new CountDownLatch(2);

        Thread t1 = new Thread(() -> {
            try { bothWaiting.countDown(); lt1.acquire(1L, 5000); }
            catch (Exception ignored) {}
        });
        Thread t2 = new Thread(() -> {
            try { bothWaiting.countDown(); lt0.acquire(2L, 5000); }
            catch (Exception ignored) {}
        });
        t1.start(); t2.start();
        bothWaiting.await();
        Thread.sleep(30);  // let threads block inside acquire

        long victim = DeadlockDetector.detectAndChooseVictim(lockMap);

        t1.interrupt(); t2.interrupt();
        t1.join(); t2.join();

        // Cycle: 1 waits for 2, 2 waits for 1 → victim = max(1, 2) = 2
        assertEquals(2L, victim);
    }

    @Test
    void threeWayCycleDetectedCorrectly() throws Exception {
        // txn 1 holds row 0, waits for row 2
        // txn 2 holds row 1, waits for row 0
        // txn 3 holds row 2, waits for row 1
        Map<Integer, LockTable> lockMap = new ConcurrentHashMap<>();

        LockTable lt0 = new LockTable(); lt0.acquire(1L, 1000); lockMap.put(0, lt0);
        LockTable lt1 = new LockTable(); lt1.acquire(2L, 1000); lockMap.put(1, lt1);
        LockTable lt2 = new LockTable(); lt2.acquire(3L, 1000); lockMap.put(2, lt2);

        CountDownLatch allWaiting = new CountDownLatch(3);
        Thread ta = new Thread(() -> { try { allWaiting.countDown(); lt2.acquire(1L, 5000); } catch (Exception ignored) {} });
        Thread tb = new Thread(() -> { try { allWaiting.countDown(); lt0.acquire(2L, 5000); } catch (Exception ignored) {} });
        Thread tc = new Thread(() -> { try { allWaiting.countDown(); lt1.acquire(3L, 5000); } catch (Exception ignored) {} });
        ta.start(); tb.start(); tc.start();
        allWaiting.await();
        Thread.sleep(30);

        long victim = DeadlockDetector.detectAndChooseVictim(lockMap);

        ta.interrupt(); tb.interrupt(); tc.interrupt();
        ta.join(); tb.join(); tc.join();

        // Cycle involves txns 1, 2, 3 → victim = max = 3
        assertEquals(3L, victim);
    }

    // ---------------------------------------------------------------
    //  Concurrent correctness
    // ---------------------------------------------------------------

    @Test
    void tenConcurrentTransactionsGetUniqueIdsAndActiveCountReachesZero()
            throws InterruptedException {

        int N = 10;
        Set<Long> txnIds = ConcurrentHashMap.newKeySet();
        CountDownLatch done = new CountDownLatch(N);

        for (int i = 0; i < N; i++) {
            new Thread(() -> {
                Transaction txn = tm.begin();
                txnIds.add(txn.txnId);
                tm.commit(txn);
                done.countDown();
            }).start();
        }

        done.await();

        assertEquals(N, txnIds.size(), "All txnIds must be unique");
        assertEquals(0, tm.getActiveTxns().size(), "No active transactions after all commit");
    }
}
