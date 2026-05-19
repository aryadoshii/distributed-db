package db.raft;

import java.util.ArrayList;
import java.util.List;

/**
 * The Raft append-only log.
 *
 * Stores LogEntry objects in memory (index 0 of the list = log index 1).
 * In a full production implementation this would be persisted to disk
 * (WAL-style) before any RPC response. For this implementation,
 * persistence is handled by checkpointing (Phase 4b).
 *
 * Key Raft invariants this class maintains:
 *   - Entries are never modified after appending (immutable log)
 *   - Entries can be TRUNCATED from the tail (follower repair)
 *   - Index is 1-based; index 0 is a sentinel (term=0, no command)
 *
 * Thread safety: RaftNode holds a single ReentrantLock that covers
 * all state including this log. Methods here are not individually
 * synchronized — they rely on the caller holding the RaftNode lock.
 */
public class RaftLog {

    // entries.get(0) is a sentinel — index=0, term=0
    // Real entries start at entries.get(1) → log index 1
    private final List<LogEntry> entries;

    public RaftLog() {
        this.entries = new ArrayList<>();
        // Sentinel at position 0 — makes index arithmetic simpler
        entries.add(new LogEntry(0, 0, null));
    }

    // ---------------------------------------------------------------
    //  Append
    // ---------------------------------------------------------------

    /**
     * Append a single new entry to the log.
     * The index is assigned automatically as lastIndex() + 1.
     *
     * @param term    the current leader term
     * @param command the SQL command bytes
     * @return the new LogEntry with its assigned index
     */
    public LogEntry append(int term, byte[] command) {
        int index = entries.size(); // next index = current size (1-based)
        LogEntry entry = new LogEntry(index, term, command);
        entries.add(entry);
        return entry;
    }

    /**
     * Append a pre-built LogEntry (used when leader sends entries to followers).
     * If an existing entry conflicts (same index, different term), truncate
     * from that index and append the new entry.
     *
     * This is the follower repair path.
     */
    public void appendOrReplace(LogEntry entry) {
        int idx = entry.getIndex();

        if (idx < entries.size()) {
            LogEntry existing = entries.get(idx);
            if (existing.getTerm() != entry.getTerm()) {
                // Conflict — truncate from this index onward
                truncateFrom(idx);
                entries.add(entry);
            }
            // Same term at same index — already have this entry, skip
        } else {
            entries.add(entry);
        }
    }

    /**
     * Append a batch of entries from the leader (follower path).
     * Handles conflicts via appendOrReplace.
     */
    public void appendAll(List<LogEntry> newEntries) {
        for (LogEntry entry : newEntries) {
            appendOrReplace(entry);
        }
    }

    // ---------------------------------------------------------------
    //  Read
    // ---------------------------------------------------------------

    /** Get entry at the given log index (1-based). */
    public LogEntry get(int index) {
        if (index < 0 || index >= entries.size()) {
            throw new IndexOutOfBoundsException("Log index out of bounds: " + index);
        }
        return entries.get(index);
    }

    /**
     * Get all entries after the given index (exclusive).
     * Used by leader to send entries to a lagging follower.
     *
     * @param afterIndex send entries with index > afterIndex
     * @return entries from afterIndex+1 to lastIndex(), inclusive
     */
    public List<LogEntry> getEntriesAfter(int afterIndex) {
        List<LogEntry> result = new ArrayList<>();
        for (int i = afterIndex + 1; i < entries.size(); i++) {
            result.add(entries.get(i));
        }
        return result;
    }

    /** The index of the last entry. 0 if log is empty (only sentinel). */
    public int lastIndex() {
        return entries.size() - 1;
    }

    /** The term of the last entry. 0 if log is empty. */
    public int lastTerm() {
        return entries.get(entries.size() - 1).getTerm();
    }

    /**
     * Check if this log has an entry matching the given index and term.
     * Used by followers to validate AppendEntries prevLogIndex/prevLogTerm.
     */
    public boolean hasEntryAt(int index, int term) {
        if (index == 0) return true; // sentinel always matches
        if (index >= entries.size()) return false;
        return entries.get(index).getTerm() == term;
    }

    /** Total number of real entries (excluding sentinel). */
    public int size() {
        return entries.size() - 1;
    }

    // ---------------------------------------------------------------
    //  Truncate (follower repair)
    // ---------------------------------------------------------------

    /**
     * Remove all entries from the given index onward.
     * Called when a follower detects a conflict with the leader's log.
     * These entries were never committed — safe to discard.
     */
    public void truncateFrom(int index) {
        while (entries.size() > index) {
            entries.remove(entries.size() - 1);
        }
    }
}
