package db.txn.mvcc;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of VersionChains, one per primary key value.
 *
 * Lives in memory alongside the B-tree. The B-tree stores the
 * latest committed bytes on disk for durability. The VersionChainMap
 * stores the in-memory version history for MVCC visibility.
 *
 * Bootstrap rule: when a key exists in the B-tree but has no chain yet
 * (first access of a pre-existing row), we create a chain with a single
 * committed version using txnId=0. TxnId 0 is the "primordial" transaction —
 * always committed, always older than any real transaction, visible to all.
 */
public class VersionChainMap {

    public static final long BOOTSTRAP_TXN_ID = 0L;

    private final ConcurrentHashMap<Integer, VersionChain> chains = new ConcurrentHashMap<>();

    /**
     * Get the chain for a key, or null if none exists yet.
     */
    public VersionChain get(int key) {
        return chains.get(key);
    }

    /**
     * Get or bootstrap a chain for a key.
     *
     * If no chain exists for this key, creates one with a single committed
     * version (txnId=0) seeded from the B-tree bytes.
     *
     * @param key           primary key
     * @param existingBytes raw bytes currently in the B-tree for this key
     */
    public VersionChain getOrBootstrap(int key, byte[] existingBytes) {
        return chains.computeIfAbsent(key, k -> {
            VersionedRow bootstrap = new VersionedRow(
                BOOTSTRAP_TXN_ID, existingBytes, false, null
            );
            bootstrap.markCommitted();
            return new VersionChain(bootstrap);
        });
    }

    /**
     * Get or create an empty chain for a key.
     * Used by InsertOp for brand-new rows that don't exist in the B-tree yet.
     * If the chain already exists (e.g., from a prior insert), returns it.
     */
    public VersionChain getOrCreate(int key) {
        return chains.computeIfAbsent(key, k -> new VersionChain());
    }

    public boolean contains(int key) { return chains.containsKey(key); }
    public void    remove(int key)   { chains.remove(key); }
    public int     size()            { return chains.size(); }
}
