package db.server;

/**
 * The network address of a cluster node.
 * Immutable value object — safe to share across threads.
 */
public record NodeAddress(int nodeId, String host, int port) {

    public String hostPort() {
        return host + ":" + port;
    }

    @Override
    public String toString() {
        return "Node" + nodeId + "(" + hostPort() + ")";
    }
}
