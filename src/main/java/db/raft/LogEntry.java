package db.raft;

/**
 * A single entry in the Raft log.
 *
 * The log is the source of truth for the entire distributed database.
 * The storage engine is just the materialized result of applying
 * log entries in order.
 *
 * Fields:
 *   index   — 1-based position in the log, monotonically increasing
 *   term    — the leader term when this entry was created
 *   command — serialized SQL string bytes to apply to the state machine
 *             null for NO-OP entries (leader's first entry each term)
 *
 * Immutable — log entries are never modified after creation.
 */
public final class LogEntry {

    public static final int NO_OP_TERM = -1; // sentinel for no-op entries

    private final int    index;
    private final int    term;
    private final byte[] command;   // null = NO-OP

    public LogEntry(int index, int term, byte[] command) {
        this.index   = index;
        this.term    = term;
        this.command = command;
    }

    /** Factory for a NO-OP entry — leader appends one on taking power. */
    public static LogEntry noOp(int index, int term) {
        return new LogEntry(index, term, null);
    }

    public int    getIndex()   { return index; }
    public int    getTerm()    { return term; }
    public byte[] getCommand() { return command; }
    public boolean isNoOp()   { return command == null; }

    @Override
    public String toString() {
        return String.format("LogEntry[idx=%d, term=%d, cmd=%s]",
            index, term, isNoOp() ? "NO-OP" : new String(command));
    }
}
