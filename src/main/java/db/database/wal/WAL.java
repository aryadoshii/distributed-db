package db.storage.wal;

import db.storage.page.Page;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Write-Ahead Log — the durability backbone of the database.
 *
 * THE GOLDEN RULE: a page must never be written to disk unless its
 * corresponding WAL record has been flushed first (WAL-before-page).
 *
 * Binary record format on disk:
 *   [total_length : 4 bytes]    ← so we can skip records during recovery
 *   [lsn          : 8 bytes]
 *   [txn_id       : 8 bytes]
 *   [type         : 4 bytes]
 *   [page_id      : 4 bytes]    ← -1 for non-UPDATE records
 *   [offset       : 4 bytes]    ← -1 for non-UPDATE records
 *   [before_len   : 4 bytes]    ← 0 for non-UPDATE records
 *   [before_image : before_len bytes]
 *   [after_len    : 4 bytes]    ← 0 for non-UPDATE records
 *   [after_image  : after_len bytes]
 */
public class WAL implements AutoCloseable {

    private static final int FIXED_HEADER_SIZE = 8 + 8 + 4 + 4 + 4; // 28 bytes: lsn + txnId + type + pageId + offset

    private final FileChannel channel;
    private final AtomicLong nextLsn;
    private final ReentrantLock writeLock;

    // In-memory buffer of unflushed records (flushed on COMMIT)
    private final List<LogRecord> buffer;

    public WAL(Path walPath) throws IOException {
        this.channel = FileChannel.open(
            walPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        );
        this.nextLsn = new AtomicLong(recoverNextLsn());
        this.writeLock = new ReentrantLock();
        this.buffer = new ArrayList<>();
    }

    /**
     * Append a BEGIN record. Call when a transaction starts.
     */
    public LogRecord begin(long txnId) throws IOException {
        return append(new LogRecord(nextLsn.getAndIncrement(), txnId, LogType.BEGIN));
    }

    /**
     * Append an UPDATE record.
     * MUST be called before modifying the page in the buffer pool.
     *
     * @param txnId       the transaction making the change
     * @param pageId      the page being modified
     * @param offset      byte offset within the page where the change starts
     * @param beforeImage the bytes at that offset BEFORE the change
     * @param afterImage  the bytes at that offset AFTER the change
     */
    public LogRecord logUpdate(long txnId, int pageId, int offset,
                               byte[] beforeImage, byte[] afterImage) throws IOException {
        LogRecord record = new LogRecord(
            nextLsn.getAndIncrement(), txnId, LogType.UPDATE,
            pageId, offset, beforeImage, afterImage
        );
        return append(record);
    }

    /**
     * Append a COMMIT record AND flush all buffered records to disk.
     * The transaction is only durable after this returns.
     */
    public LogRecord commit(long txnId) throws IOException {
        LogRecord record = new LogRecord(
            nextLsn.getAndIncrement(), txnId, LogType.COMMIT
        );
        append(record);
        flush();  // force to disk before acknowledging commit
        return record;
    }

    /**
     * Append an ABORT record and flush.
     */
    public LogRecord abort(long txnId) throws IOException {
        LogRecord record = new LogRecord(
            nextLsn.getAndIncrement(), txnId, LogType.ABORT
        );
        append(record);
        flush();
        return record;
    }

    /**
     * Flush all buffered records to disk.
     * Called by the buffer pool before evicting a dirty page.
     */
    public void flush() throws IOException {
        writeLock.lock();
        try {
            for (LogRecord record : buffer) {
                writeRecordToDisk(record);
            }
            channel.force(false);
            buffer.clear();
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Read all records from the WAL file for crash recovery.
     * Called once at database startup by RecoveryManager.
     */
    public List<LogRecord> readAll() throws IOException {
        List<LogRecord> records = new ArrayList<>();
        channel.position(0);
        long fileSize = channel.size();

        while (channel.position() < fileSize) {
            try {
                LogRecord record = readNextRecord();
                if (record != null) records.add(record);
            } catch (IOException e) {
                // Partial record at end of file — crash during write, stop here
                break;
            }
        }
        return records;
    }

    public long getCurrentLsn() {
        return nextLsn.get();
    }

    // --- Private serialization ---

    private LogRecord append(LogRecord record) throws IOException {
        writeLock.lock();
        try {
            buffer.add(record);
            return record;
        } finally {
            writeLock.unlock();
        }
    }

    private void writeRecordToDisk(LogRecord r) throws IOException {
        byte[] before = r.getBeforeImage() != null ? r.getBeforeImage() : new byte[0];
        byte[] after  = r.getAfterImage()  != null ? r.getAfterImage()  : new byte[0];

        int totalLength = FIXED_HEADER_SIZE + 4 + before.length + 4 + after.length;
        ByteBuffer buf = ByteBuffer.allocate(4 + totalLength);

        buf.putInt(totalLength);
        buf.putLong(r.getLsn());
        buf.putLong(r.getTxnId());
        buf.putInt(r.getType().ordinal());
        buf.putInt(r.getPageId());
        buf.putInt(r.getOffset());
        buf.putInt(before.length);
        buf.put(before);
        buf.putInt(after.length);
        buf.put(after);
        buf.flip();

        while (buf.hasRemaining()) {
            channel.write(buf, channel.size());
        }
    }

    private LogRecord readNextRecord() throws IOException {
        ByteBuffer lenBuf = ByteBuffer.allocate(4);
        if (channel.read(lenBuf) < 4) return null;
        lenBuf.flip();
        int totalLength = lenBuf.getInt();

        ByteBuffer buf = ByteBuffer.allocate(totalLength);
        channel.read(buf);
        buf.flip();

        long lsn      = buf.getLong();
        long txnId    = buf.getLong();
        LogType type  = LogType.values()[buf.getInt()];
        int pageId    = buf.getInt();
        int offset    = buf.getInt();

        int beforeLen = buf.getInt();
        byte[] before = new byte[beforeLen];
        buf.get(before);

        int afterLen  = buf.getInt();
        byte[] after  = new byte[afterLen];
        buf.get(after);

        return new LogRecord(lsn, txnId, type, pageId, offset,
            beforeLen > 0 ? before : null,
            afterLen  > 0 ? after  : null);
    }

    private long recoverNextLsn() throws IOException {
        // Scan to find the highest LSN already in the file
        long maxLsn = 0;
        try {
            List<LogRecord> existing = readAll();
            for (LogRecord r : existing) {
                maxLsn = Math.max(maxLsn, r.getLsn());
            }
        } catch (Exception ignored) {}
        return maxLsn + 1;
    }

    @Override
    public void close() throws IOException {
        flush();
        channel.close();
    }
}