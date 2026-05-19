package db.raft;

/**
 * The three roles a Raft node can occupy.
 * A node is always in exactly one role at any moment.
 *
 * Transitions:
 *   FOLLOWER  → CANDIDATE  : election timeout fires, no heartbeat received
 *   CANDIDATE → LEADER     : majority votes received
 *   CANDIDATE → FOLLOWER   : higher term seen, or another leader discovered
 *   LEADER    → FOLLOWER   : higher term seen in any incoming RPC
 */
public enum RaftRole {
    FOLLOWER,
    CANDIDATE,
    LEADER
}
