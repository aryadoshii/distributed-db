package db.raft;

import java.util.List;

/**
 * Immutable configuration for a Raft cluster node.
 *
 * Contains this node's identity and the addresses of all peers.
 * Timeout values follow the Raft paper recommendation:
 *   - heartbeat interval: ~50ms
 *   - election timeout: random in [150ms, 300ms]
 *
 * The randomized election timeout is what prevents all followers
 * from starting elections simultaneously after a leader failure.
 * With random timeouts, one follower almost always fires first,
 * wins the election, and suppresses the others with heartbeats.
 */
public final class RaftConfig {

    private final int         nodeId;
    private final List<Integer> peerIds;
    private final int         heartbeatIntervalMs;
    private final int         electionTimeoutMinMs;
    private final int         electionTimeoutMaxMs;

    public RaftConfig(int nodeId,
                      List<Integer> peerIds,
                      int heartbeatIntervalMs,
                      int electionTimeoutMinMs,
                      int electionTimeoutMaxMs) {
        this.nodeId                = nodeId;
        this.peerIds               = List.copyOf(peerIds);
        this.heartbeatIntervalMs   = heartbeatIntervalMs;
        this.electionTimeoutMinMs  = electionTimeoutMinMs;
        this.electionTimeoutMaxMs  = electionTimeoutMaxMs;
    }

    /** Standard 3-node cluster configuration. */
    public static RaftConfig standard(int nodeId, List<Integer> peerIds) {
        return new RaftConfig(nodeId, peerIds, 50, 150, 300);
    }

    /** Fast configuration for unit tests — shorter timeouts. */
    public static RaftConfig forTest(int nodeId, List<Integer> peerIds) {
        return new RaftConfig(nodeId, peerIds, 20, 60, 120);
    }

    public int         nodeId()               { return nodeId; }
    public List<Integer> peerIds()            { return peerIds; }
    public int         heartbeatIntervalMs()  { return heartbeatIntervalMs; }
    public int         electionTimeoutMinMs() { return electionTimeoutMinMs; }
    public int         electionTimeoutMaxMs() { return electionTimeoutMaxMs; }

    /** Cluster size = this node + all peers. */
    public int clusterSize() { return 1 + peerIds.size(); }

    /** Minimum votes needed to win an election. */
    public int majority()    { return clusterSize() / 2 + 1; }
}
