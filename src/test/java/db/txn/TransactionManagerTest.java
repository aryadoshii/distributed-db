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
        Transaction txn1 = tm.begin();
        VersionChain chain = new VersionChain();
        VersionedRow v = chain.prepend(txn1.getTxnId(), new byte[]{42}, false);
        txn1.addToWriteSet(v);
        tm.commit(txn1);

        Transaction txn2 = tm.begin();
        VersionedRow visible = chain.getVisible(txn2.getSnapshot());
        tm.abort(txn2);

        assertNotNull(visible);
        assertEquals(42, visible.getValue()[0]);
    }

    @Test
    void uncommittedVersionIsNotVisibleToConcurrentSnapshot() {
        Transaction txn1 = tm.begin();
        Transaction txn2 = tm.begin();

        VersionChain chain = new VersionChain();
        chain.prepend(txn1.getTxnId(), new byte[]{7}, false);

        VersionedRow visible = chain.getVisible(txn2.getSnapshot());
        tm.abort(txn1);
        tm.abort(txn2);

        assertNull(visible);
    }

    @Test
    void futureVersionIsNotVisibleToEarlierSnapshot() {
        Transaction txn1 = tm.begin();
        Transaction txn2 = tm.begin();

        VersionChain chain = new VersionChain();
        VersionedRow v = chain.prepend(txn2.getTxnId(), new byte[]{99}, false);
        txn2.addToWriteSet(v);
        tm.commit(txn2);

        VersionedRow visible = chain.getVisible(txn1.getSnapshot());
        tm.abort(txn1);

        assertNull(visible);
    }

    @Test
    void ownWriteIsAlwaysVisible() {
        Transaction txn = tm.begin();
        VersionChain chain = new VersionChain();
        chain.prepend(txn.getTxnId(), new byte[]{55}, false);

        VersionedRow visible = chain.getVisible(txn.getSnapshot());
        tm.abort(txn);

        assertNotNull(visible);
        assertEquals(55, visible.getValue()[0]);
    }

    @Test
    void deletedTombstoneVersionHandledByGetVisible() {
        Transaction txn1 = tm.begin();
        VersionChain chain = new VersionChain();
        VersionedRow tomb = chain.prepend(txn1.getTxnId(), null, true);
        txn1.addToWriteSet(tomb);
        tm.commit(txn1);

        Transaction txn2 = tm.begin();
        // getVisible returns null for tombstones (caller need not check .isDeleted())
        VersionedRow visible = chain.getVisible(txn2.getSnapshot());
        tm.abort(txn2);

        assertNull(visible);
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

        assertTrue(t1.getTxnId() < t2.getTxnId());
        assertTrue(t2.getTxnId() < t3.getTxnId());
    }

    @Test
    void beginCapturesActiveTxnsInSnapshot() {
        Transaction t1 = tm.begin();
        Transaction t2 = tm.begin();

        assertTrue(t2.getSnapshot().activeTxns.contains(t1.getTxnId()));
        assertFalse(t2.getSnapshot().activeTxns.contains(t2.getTxnId()));

        tm.abort(t1);
        tm.abort(t2);
    }

    @Test
    void commitMarksVersionsAsCommitted() {
        Transaction txn = tm.begin();
        VersionChain chain = new VersionChain();
        VersionedRow v = chain.prepend(txn.getTxnId(), new byte[]{1}, false);
        txn.addToWriteSet(v);

        assertFalse(v.isCommitted());
        tm.commit(txn);
        assertTrue(v.isCommitted());
    }

    @Test
    void abortLeavesVersionsUncommitted() {
        Transaction txn = tm.begin();
        VersionChain chain = new VersionChain();
        VersionedRow v = chain.prepend(txn.getTxnId(), new byte[]{2}, false);
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

        lm.acquireWriteLock(1L, 100);

        CountDownLatch waiterStarted  = new CountDownLatch(1);
        CountDownLatch waiterAcquired = new CountDownLatch(1);

        Thread waiterThread = new Thread(() -> {
            try {
                waiterStarted.countDown();
                lm.acquireWriteLock(2L, 100);
                waiterAcquired.countDown();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        waiterThread.start();
        waiterStarted.await();

        Thread.sleep(50);
        assertFalse(waiterAcquired.getCount() == 0, "Waiter should still be blocked");

        lm.releaseAll(1L, Set.of(100));

        waiterAcquired.await();
        waiterThread.join();

        lm.releaseAll(2L, Set.of(100));
    }

    @Test
    void releaseAllReleasesAllLocksHeldByTransaction() throws Exception {
        LockManager lm = new LockManager();

        lm.acquireWriteLock(10L, 1);
        lm.acquireWriteLock(10L, 2);
        lm.acquireWriteLock(10L, 3);
        lm.releaseAll(10L, Set.of(1, 2, 3));

        assertDoesNotThrow(() -> lm.acquireWriteLock(11L, 1));
        assertDoesNotThrow(() -> lm.acquireWriteLock(11L, 2));
        assertDoesNotThrow(() -> lm.acquireWriteLock(11L, 3));
        lm.releaseAll(11L, Set.of(1, 2, 3));
    }

    // ---------------------------------------------------------------
    //  DeadlockDetector
    // ---------------------------------------------------------------

    @Test
    void noDeadlockReturnsMinusOne() {
        Map<Integer, LockTable> lockMap = new ConcurrentHashMap<>();
        LockTable lt = new LockTable();
        try { lt.acquire(1L, 100); } catch (Exception ignored) {}
        lockMap.put(0, lt);

        assertEquals(-1L, DeadlockDetector.detectAndChooseVictim(lockMap));
    }

    @Test
    void simpleCycleABDetectedReturnsHighestTxnId() throws Exception {
        Map<Integer, LockTable> lockMap = new ConcurrentHashMap<>();

        LockTable lt0 = new LockTable();
        lt0.acquire(1L, 1000);
        lockMap.put(0, lt0);

        LockTable lt1 = new LockTable();
        lt1.acquire(2L, 1000);
        lockMap.put(1, lt1);

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
        Thread.sleep(30);

        long victim = DeadlockDetector.detectAndChooseVictim(lockMap);

        t1.interrupt(); t2.interrupt();
        t1.join(); t2.join();

        assertEquals(2L, victim);
    }

    @Test
    void threeWayCycleDetectedCorrectly() throws Exception {
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
                txnIds.add(txn.getTxnId());
                tm.commit(txn);
                done.countDown();
            }).start();
        }

        done.await();

        assertEquals(N, txnIds.size(), "All txnIds must be unique");
        assertEquals(0, tm.getActiveTxns().size(), "No active transactions after all commit");
    }
}
