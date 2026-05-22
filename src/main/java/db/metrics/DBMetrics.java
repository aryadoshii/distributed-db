package db.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;

/**
 * Named metric handles for all database subsystems.
 * Pre-built so callers avoid repeated tag lookups.
 *
 * Convention: db.<subsystem>.<operation>
 */
public final class DBMetrics {

    // B-tree
    public static final Counter BTREE_INSERTS    = MetricsRegistry.counter("db.btree.ops", "op", "insert");
    public static final Counter BTREE_SEARCHES   = MetricsRegistry.counter("db.btree.ops", "op", "search");
    public static final Counter BTREE_DELETES    = MetricsRegistry.counter("db.btree.ops", "op", "delete");
    public static final Counter BTREE_RANGE_SCANS= MetricsRegistry.counter("db.btree.ops", "op", "range_scan");

    // Buffer pool
    public static final Counter BUFFER_HITS      = MetricsRegistry.counter("db.buffer.pool", "result", "hit");
    public static final Counter BUFFER_MISSES    = MetricsRegistry.counter("db.buffer.pool", "result", "miss");
    public static final Counter BUFFER_EVICTIONS = MetricsRegistry.counter("db.buffer.pool", "result", "eviction");

    // WAL
    public static final Counter WAL_RECORDS_WRITTEN = MetricsRegistry.counter("db.wal.records", "op", "write");
    public static final Counter WAL_FLUSHES          = MetricsRegistry.counter("db.wal.flushes");

    // Transactions
    public static final Counter TXN_BEGINS    = MetricsRegistry.counter("db.txn.ops", "op", "begin");
    public static final Counter TXN_COMMITS   = MetricsRegistry.counter("db.txn.ops", "op", "commit");
    public static final Counter TXN_ABORTS    = MetricsRegistry.counter("db.txn.ops", "op", "abort");
    public static final Counter TXN_DEADLOCKS = MetricsRegistry.counter("db.txn.deadlocks");

    // SQL
    public static final Timer SQL_QUERY_LATENCY = MetricsRegistry.timer("db.sql.query.latency", "type", "select");
    public static final Timer SQL_WRITE_LATENCY = MetricsRegistry.timer("db.sql.query.latency", "type", "write");

    // Raft
    public static final Counter RAFT_ELECTIONS      = MetricsRegistry.counter("db.raft.elections");
    public static final Counter RAFT_COMMITS        = MetricsRegistry.counter("db.raft.commits");
    public static final Counter RAFT_APPENDS        = MetricsRegistry.counter("db.raft.log.appends");
    public static final Timer   RAFT_COMMIT_LATENCY = MetricsRegistry.timer("db.raft.commit.latency");

    private DBMetrics() {}
}
