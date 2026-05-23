package db.storage.wal;

/**
 * A single record in the Write-Ahead Log.
 *
 * For UPDATE records, we store:
 *   - which page was modified
 *   - at what byte offset
 *   - what the bytes looked like BEFORE (for UNDO on abort)
 *   - what the bytes look like AFTER  (for REDO on crash recovery)
 *
 * For BEGIN/COMMIT/ABORT, only txnId and type matter.
 * For CHECKPOINT, no extra fields needed.
 */
public class LogRecord {

    private final long lsn;          // Log Sequence Number — unique, monotonically increasing
    private final long txnId;        // which transaction wrote this
    private final LogType type;

    // UPDATE-only fields
    private final int pageId;
    private final int offset;
    private final byte[] beforeImage;
    private final byte[] afterImage;

    /** Constructor for BEGIN / COMMIT / ABORT / CHECKPOINT */
    public LogRecord(long lsn, long txnId, LogType type) {
        this(lsn, txnId, type, -1, -1, null, null);
    }

    /** Constructor for UPDATE */
    public LogRecord(long lsn, long txnId, LogType type,
                     int pageId, int offset,
                     byte[] beforeImage, byte[] afterImage) {
        this.lsn = lsn;
        this.txnId = txnId;
        this.type = type;
        this.pageId = pageId;
        this.offset = offset;
        this.beforeImage = beforeImage;
        this.afterImage = afterImage;
    }

    public long getLsn()            { return lsn; }
    public long getTxnId()          { return txnId; }
    public LogType getType()        { return type; }
    public int getPageId()          { return pageId; }
    public int getOffset()          { return offset; }
    public byte[] getBeforeImage()  { return beforeImage; }
    public byte[] getAfterImage()   { return afterImage; }

    @Override
    public String toString() {
        return String.format("LogRecord[lsn=%d, txn=%d, type=%s, page=%d]",
            lsn, txnId, type, pageId);
    }
}