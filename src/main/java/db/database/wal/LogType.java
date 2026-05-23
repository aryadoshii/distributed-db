package db.storage.wal;

public enum LogType {
    BEGIN,      // transaction started
    UPDATE,     // page was modified (stores before/after image)
    COMMIT,     // transaction committed
    ABORT,      // transaction aborted
    CHECKPOINT  // all dirty pages flushed to disk at this point
}