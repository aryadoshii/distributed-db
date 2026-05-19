package db.network;

import db.server.NodeAddress;

/**
 * Placeholder for a future Netty-based TCP RPC client.
 *
 * In production this would open a TCP connection to a peer's
 * RaftRpcServer and send protobuf-encoded RequestVote /
 * AppendEntries messages. For now all tests use the in-process
 * transport injected at startup.
 */
public class RaftRpcClient {

    private final NodeAddress target;

    public RaftRpcClient(NodeAddress target) {
        this.target = target;
    }

    public NodeAddress getTarget() { return target; }
}
