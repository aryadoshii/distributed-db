package db.raft.rpc;

/**
 * RequestVote RPC — sent by a CANDIDATE to solicit votes.
 *
 * A follower grants its vote if:
 *   1. candidate's term >= votedFor's term (or votedFor is null)
 *   2. candidate's log is at least as up-to-date as the follower's
 *
 * "Up-to-date" comparison (§5.4.1 of Raft paper):
 *   - Higher lastLogTerm wins
 *   - If equal lastLogTerm: higher lastLogIndex wins
 */
public final class RequestVote {

    // ---- Request ----
    public record Request(
        int  term,           // candidate's current term
        int  candidateId,    // who is requesting the vote
        int  lastLogIndex,   // index of candidate's last log entry
        int  lastLogTerm     // term of candidate's last log entry
    ) {}

    // ---- Response ----
    public record Response(
        int     term,        // responder's current term (candidate updates if stale)
        boolean voteGranted  // true if the vote was granted
    ) {}
}
