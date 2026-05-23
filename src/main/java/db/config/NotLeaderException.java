package db.server;

/**
 * Thrown when a follower node receives a write request.
 * Carries the current leader's node ID so the client can redirect.
 *
 * The client catches this, updates its known leader address,
 * and retries the request against the correct node.
 */
public class NotLeaderException extends Exception {

    private final int    leaderId;
    private final String leaderAddress;  // "host:port"

    public NotLeaderException(int leaderId, String leaderAddress) {
        super("Not the leader. Current leader: node "
              + leaderId + " at " + leaderAddress);
        this.leaderId      = leaderId;
        this.leaderAddress = leaderAddress;
    }

    /** -1 if leader is unknown (cluster is mid-election). */
    public int    getLeaderId()      { return leaderId; }
    public String getLeaderAddress() { return leaderAddress; }
}
