package db.chaos;

import db.raft.RaftConfig;
import db.raft.RaftNode;
import db.raft.rpc.AppendEntries;
import db.raft.rpc.RequestVote;
import db.raft.statemachine.DatabaseStateMachine;
import db.server.Catalog;
import db.server.DBServer;
import db.server.NodeAddress;
import db.sql.executor.Planner;
import db.storage.buffer.BufferPool;
import db.storage.page.DiskManager;
import db.txn.TransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * Shared in-process cluster builder for chaos tests.
 *
 * Builds N DBServer nodes wired with supplyAsync transport.
 * Node IDs are 0-based so NotLeaderException.getLeaderId() maps
 * directly to the list index used by ClusterClient.
 */
public class Phase5ClusterBuilder {

    private Phase5ClusterBuilder() {}

    public static List<DBServer> build(int n, Path tempDir) throws Exception {
        Map<Integer, NodeAddress> addresses = new HashMap<>();
        for (int i = 0; i < n; i++) {
            addresses.put(i, new NodeAddress(i, "localhost", 9000 + i));
        }

        List<DBServer> servers = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            List<Integer> peers = new ArrayList<>();
            for (int j = 0; j < n; j++) { if (j != i) peers.add(j); }

            Path   dbFile = Files.createTempFile(tempDir, "node" + i + "-", ".db");
            DiskManager      dm  = new DiskManager(dbFile);
            BufferPool       bp  = new BufferPool(256, dm);
            Catalog          cat = new Catalog();
            TransactionManager tm = new TransactionManager();
            Planner          pl  = new Planner(cat, bp, tm);
            DatabaseStateMachine sm = new DatabaseStateMachine(pl);

            RaftConfig config = RaftConfig.forTest(i, peers);
            RaftNode   raft   = new RaftNode(config, sm);
            DBServer   server = new DBServer(raft, sm, addresses.get(i), addresses);
            servers.add(server);
        }

        wireAndStart(servers);
        return servers;
    }

    /** Wire supplyAsync transport and start all servers. */
    public static void wireAndStart(List<DBServer> servers) {
        for (int i = 0; i < servers.size(); i++) {
            final List<DBServer> snap = servers;
            servers.get(i).start(
                buildRvTransport(snap),
                buildAeTransport(snap)
            );
        }
    }

    public static BiFunction<Integer, RequestVote.Request,
                             CompletableFuture<RequestVote.Response>>
    buildRvTransport(List<DBServer> servers) {
        return (peerId, req) -> CompletableFuture.supplyAsync(
            () -> servers.get(peerId).handleRequestVote(req)
        );
    }

    // Alias used by FollowerRejoinChaosTest
    public static BiFunction<Integer, RequestVote.Request,
                             CompletableFuture<RequestVote.Response>>
    buildTransport(List<DBServer> servers, int ignoredNodeId) {
        return buildRvTransport(servers);
    }

    public static BiFunction<Integer, AppendEntries.Request,
                             CompletableFuture<AppendEntries.Response>>
    buildAeTransport(List<DBServer> servers) {
        return (peerId, req) -> CompletableFuture.supplyAsync(
            () -> servers.get(peerId).handleAppendEntries(req)
        );
    }

    // Alias used by FollowerRejoinChaosTest
    public static BiFunction<Integer, AppendEntries.Request,
                             CompletableFuture<AppendEntries.Response>>
    buildAppendTransport(List<DBServer> servers, int ignoredNodeId) {
        return buildAeTransport(servers);
    }
}
