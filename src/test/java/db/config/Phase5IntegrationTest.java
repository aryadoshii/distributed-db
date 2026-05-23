package db.server;

import db.client.ClusterClient;
import db.client.jdbc.DBDriver;
import db.raft.RaftConfig;
import db.raft.RaftNode;
import db.raft.statemachine.DatabaseStateMachine;
import db.sql.executor.Planner;
import db.storage.buffer.BufferPool;
import db.storage.page.DiskManager;
import db.txn.TransactionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end Phase 5 integration tests.
 *
 * Each test builds its own 3-node in-process cluster (via supplyAsync transport),
 * runs SQL through the full Raft → DatabaseStateMachine → Planner → MVCC stack,
 * and verifies correctness at the JDBC level.
 *
 * Node IDs are 0-based so NotLeaderException.getLeaderId() maps directly
 * to the index used by ClusterClient.
 */
class Phase5IntegrationTest {

    @TempDir Path tempDir;

    private final List<DBServer> allServers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (DBServer s : allServers) {
            try { s.stop(); } catch (Exception ignored) {}
        }
        allServers.clear();
    }

    // ---------------------------------------------------------------
    //  Cluster bootstrap
    // ---------------------------------------------------------------

    @Test
    void clusterElectsLeader() {
        List<DBServer> cluster = buildCluster(3);
        DBServer leader = waitForLeader(cluster, 500);

        assertTrue(leader.isLeader());
        long followers = cluster.stream().filter(s -> !s.isLeader()).count();
        assertEquals(2, followers);
    }

    // ---------------------------------------------------------------
    //  SQL write path
    // ---------------------------------------------------------------

    @Test
    void writePathCreateAndInsert() {
        List<DBServer> cluster = buildCluster(3);
        waitForLeader(cluster, 500);

        String createResult = execute(cluster, "CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(255))");
        assertTrue(createResult.contains("created"),
            "Expected 'created' in CREATE TABLE result: " + createResult);

        String insertResult = execute(cluster, "INSERT INTO users (id, name) VALUES (1, 'alice')");
        assertTrue(insertResult.contains("affected_rows"),
            "Expected 'affected_rows' in INSERT result: " + insertResult);

        execute(cluster, "INSERT INTO users (id, name) VALUES (2, 'bob')");
    }

    // ---------------------------------------------------------------
    //  SQL read path
    // ---------------------------------------------------------------

    @Test
    void readPathSelect() {
        List<DBServer> cluster = buildCluster(3);
        waitForLeader(cluster, 500);

        execute(cluster, "CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(255))");
        execute(cluster, "INSERT INTO users (id, name) VALUES (1, 'alice')");
        execute(cluster, "INSERT INTO users (id, name) VALUES (2, 'bob')");

        String allRows = query(cluster, "SELECT * FROM users");
        assertFalse(allRows.isBlank(), "SELECT * returned empty result");

        String filtered = query(cluster, "SELECT * FROM users WHERE id = 1");
        assertTrue(filtered.contains("alice"),
            "Expected 'alice' in filtered result: " + filtered);
    }

    // ---------------------------------------------------------------
    //  Consistency across nodes
    // ---------------------------------------------------------------

    @Test
    void consistencyAcrossNodes() throws InterruptedException {
        List<DBServer> cluster = buildCluster(3);
        waitForLeader(cluster, 500);

        execute(cluster, "CREATE TABLE events (id INT PRIMARY KEY, val INT)");
        execute(cluster, "INSERT INTO events (id, val) VALUES (1, 10)");
        execute(cluster, "INSERT INTO events (id, val) VALUES (2, 20)");

        // Give all nodes time to apply committed entries
        Thread.sleep(200);

        for (DBServer server : cluster) {
            int idx = server.getStateMachine().lastAppliedIndex();
            assertTrue(idx >= 2,
                "Node " + server.getNodeId() + " lastAppliedIndex=" + idx + " < 2");
        }
    }

    // ---------------------------------------------------------------
    //  Leader failover + continued operation
    // ---------------------------------------------------------------

    @Test
    void leaderFailoverContinuedOperation() throws InterruptedException {
        List<DBServer> cluster = buildCluster(3);
        waitForLeader(cluster, 500);

        execute(cluster, "CREATE TABLE log (id INT PRIMARY KEY, val INT)");
        execute(cluster, "INSERT INTO log (id, val) VALUES (1, 10)");

        DBServer oldLeader = cluster.stream()
            .filter(DBServer::isLeader).findFirst().orElseThrow();
        oldLeader.stop();

        List<DBServer> remaining = cluster.stream()
            .filter(s -> s != oldLeader)
            .collect(toList());

        waitForLeader(remaining, 2000);

        // Insert via new leader
        execute(remaining, "INSERT INTO log (id, val) VALUES (2, 20)");

        // Wait for apply to propagate across remaining nodes
        Thread.sleep(200);

        String result = query(remaining, "SELECT * FROM log");
        assertFalse(result.isBlank(), "Expected rows after failover");
    }

    // ---------------------------------------------------------------
    //  JDBC layer
    // ---------------------------------------------------------------

    @Test
    void jdbcLayer() throws Exception {
        List<DBServer> cluster = buildCluster(3);
        waitForLeader(cluster, 500);

        ClusterClient client = new ClusterClient(cluster);

        Properties props = new Properties();
        props.put("cluster.client", client);

        Connection conn = new DBDriver().connect("jdbc:distributeddb://localhost/test", props);
        assertNotNull(conn, "Expected non-null Connection from DBDriver.connect()");

        try (conn) {
            // CREATE TABLE via execute()
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE jdbc_test (id INT PRIMARY KEY, val INT)");
            }

            // INSERT via executeUpdate()
            try (Statement stmt = conn.createStatement()) {
                int updated = stmt.executeUpdate(
                    "INSERT INTO jdbc_test (id, val) VALUES (1, 42)");
                assertTrue(updated >= 0, "executeUpdate should return >= 0");
            }

            // SELECT via executeQuery()
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery("SELECT * FROM jdbc_test");
                assertTrue(rs.next(), "Expected at least one row in ResultSet");
            }
        }
    }

    // ---------------------------------------------------------------
    //  Cluster builder — in-process supplyAsync transport, 0-based IDs
    // ---------------------------------------------------------------

    private List<DBServer> buildCluster(int size) {
        Map<Integer, DBServer>    serverMap = new LinkedHashMap<>();
        Map<Integer, NodeAddress> addresses = new HashMap<>();

        for (int id = 0; id < size; id++) {
            addresses.put(id, new NodeAddress(id, "localhost", 9000 + id));
        }

        for (int id = 0; id < size; id++) {
            final int nodeId = id;
            List<Integer> peers = IntStream.range(0, size)
                .filter(i -> i != nodeId)
                .boxed()
                .collect(toList());

            try {
                Path dbFile = tempDir.resolve("node-" + nodeId + ".db");
                DiskManager      dm  = new DiskManager(dbFile);
                BufferPool       bp  = new BufferPool(64, dm);
                Catalog          cat = new Catalog();
                TransactionManager tm = new TransactionManager();
                Planner          pl  = new Planner(cat, bp, tm);

                DatabaseStateMachine sm     = new DatabaseStateMachine(pl);
                RaftConfig           config = RaftConfig.forTest(nodeId, peers);
                RaftNode             node   = new RaftNode(config, sm);
                NodeAddress          self   = addresses.get(nodeId);

                DBServer server = new DBServer(node, sm, self, addresses);
                serverMap.put(nodeId, server);
                allServers.add(server);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create node " + nodeId, e);
            }
        }

        List<DBServer> list = new ArrayList<>(serverMap.values());

        for (Map.Entry<Integer, DBServer> entry : serverMap.entrySet()) {
            entry.getValue().start(
                (peerId, req) -> CompletableFuture.supplyAsync(
                    () -> serverMap.get(peerId).handleRequestVote(req)
                ),
                (peerId, req) -> CompletableFuture.supplyAsync(
                    () -> serverMap.get(peerId).handleAppendEntries(req)
                )
            );
        }

        return list;
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private DBServer waitForLeader(List<DBServer> cluster, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (DBServer s : cluster) {
                if (s.isLeader()) return s;
            }
            sleepQuietly(20);
        }
        throw new AssertionError("No leader elected within " + timeoutMs + "ms");
    }

    private String execute(List<DBServer> cluster, String sql) {
        DBServer leader = waitForLeader(cluster, 2000);
        try {
            return leader.execute(sql);
        } catch (NotLeaderException e) {
            // Leadership changed between find and call — retry
            return execute(cluster, sql);
        } catch (Exception e) {
            throw new RuntimeException("execute() failed: " + e.getMessage(), e);
        }
    }

    private String query(List<DBServer> cluster, String sql) {
        DBServer leader = waitForLeader(cluster, 2000);
        try {
            return leader.query(sql);
        } catch (NotLeaderException e) {
            return query(cluster, sql);
        }
    }

    private void sleepQuietly(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
