package db.raft.rpc;

import db.raft.LogEntry;
import java.util.List;

/**
 * AppendEntries RPC — sent by the LEADER for two purposes:
 *   1. Heartbeat (entries is empty) — suppresses follower election timers
 *   2. Log replication (entries non-empty) — replicates new log entries
 *
 * The prevLogIndex/prevLogTerm consistency check ensures the
 * Log Matching invariant: if two logs agree at index N,
 * they agree on all entries before N.
 *
 * A follower rejects AppendEntries if its log doesn't contain
 * an entry at prevLogIndex with prevLogTerm. The leader then
 * decrements nextIndex for that follower and retries.
 */
public final class AppendEntries {

    // ---- Request ----
    public record Request(
        int            term,           // leader's current term
        int            leaderId,       // so followers can redirect clients
        int            prevLogIndex,   // index of entry immediately before new ones
        int            prevLogTerm,    // term of prevLogIndex entry
        List<LogEntry> entries,        // empty for heartbeat
        int            leaderCommit   // leader's commitIndex
    ) {}

    // ---- Response ----
    public record Response(
        int     term,       // follower's current term (leader steps down if stale)
        boolean success,    // true if follower contained entry matching prevLog*
        int     matchIndex  // highest log index known to be replicated on this follower
    ) {}
}
