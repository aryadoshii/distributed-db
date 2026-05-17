package db.txn.mvcc;

/**
 * A single version of a row in the version chain.
 * Versions are linked newest-first via {@code prev}.
 */
public final class VersionedRow {

    public final long         txnId;      // writer's transaction ID
    public final byte[]       value;      // serialized row data (null if deleted)
    public final boolean      deleted;    // true = tombstone
    volatile     boolean      committed;  // set true on commit, remains false on abort
    public final VersionedRow prev;       // older version, or null if first

    public VersionedRow(long txnId, byte[] value, boolean deleted, VersionedRow prev) {
        this.txnId     = txnId;
        this.value     = value;
        this.deleted   = deleted;
        this.committed = false;
        this.prev      = prev;
    }

    public boolean isCommitted() { return committed; }
    public void markCommitted() { this.committed = true; }
    public void markAborted()   { /* committed stays false; version becomes permanently invisible */ }
}
