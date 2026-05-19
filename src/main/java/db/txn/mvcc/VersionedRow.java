package db.txn.mvcc;

/**
 * A single version of a row in the version chain.
 * Versions are linked newest-first via {@code prev}.
 */
public final class VersionedRow {

    private final long         txnId;
    private final byte[]       value;
    private final boolean      deleted;
    private volatile boolean   committed;
    private final VersionedRow prev;

    public VersionedRow(long txnId, byte[] value, boolean deleted, VersionedRow prev) {
        this.txnId     = txnId;
        this.value     = value;
        this.deleted   = deleted;
        this.committed = false;
        this.prev      = prev;
    }

    public long         getTxnId()     { return txnId; }
    public byte[]       getValue()     { return value; }
    public boolean      isDeleted()    { return deleted; }
    public boolean      isCommitted()  { return committed; }
    public VersionedRow getPrev()      { return prev; }

    public void markCommitted() { this.committed = true; }
    public void markAborted()   { /* committed stays false; version becomes permanently invisible */ }
}
