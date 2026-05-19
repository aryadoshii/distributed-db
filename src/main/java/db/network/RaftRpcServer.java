package db.network;

import db.raft.rpc.AppendEntries;
import db.raft.rpc.RequestVote;
import db.server.DBServer;

/**
 * Placeholder for a future Netty-based TCP RPC server.
 *
 * In production this would bind a port and route incoming protobuf
 * messages to DBServer.handleRequestVote / handleAppendEntries.
 * For now all tests use the in-process transport injected at startup.
 */
public class RaftRpcServer {

    private final DBServer server;
    private final int      port;

    public RaftRpcServer(DBServer server, int port) {
        this.server = server;
        this.port   = port;
    }

    public void start() {
        throw new UnsupportedOperationException("TCP transport not yet implemented");
    }

    public void stop() {
        // no-op until implemented
    }
}
