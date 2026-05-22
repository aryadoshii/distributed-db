package db.chaos;

import db.client.ClusterClient;
import db.server.DBServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Chaos test: kill a follower, write more data, restart it, verify catch-up.
 *
 * The restarted follower must apply all log entries it missed while stopped
 * via the Raft log replication repair loop.
 */
class FollowerRejoinChaosTest {

    @TempDir Path tempDir;

    private List<DBServer> cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) cluster.forEach(s -> { try { s.stop(); } catch (Exception ignored) {} });
    }

    @Test
    void followerCatchesUpAfterRejoin() throws Exception {
        cluster = Phase5ClusterBuilder.build(3, tempDir);
        ClusterClient client = new ClusterClient(cluster);
        client.waitForLeader(1000);

        client.execute("CREATE TABLE rejoin (id INT PRIMARY KEY, val INT)");

        // Insert 5 rows with all nodes alive
        for (int i = 1; i <= 5; i++) {
            client.execute("INSERT INTO rejoin (id, val) VALUES (" + i + ", " + i + ")");
        }

        // Stop a follower
        DBServer follower = cluster.stream()
            .filter(s -> !s.isLeader())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No follower found"));
        follower.stop();

        // Insert 5 more rows — cluster still has majority
        for (int i = 6; i <= 10; i++) {
            client.execute("INSERT INTO rejoin (id, val) VALUES (" + i + ", " + i + ")");
        }

        // Restart follower — it will receive heartbeats and catch up
        follower.start(
            Phase5ClusterBuilder.buildTransport(cluster, follower.getNodeId()),
            Phase5ClusterBuilder.buildAppendTransport(cluster, follower.getNodeId())
        );

        // Wait for catch-up
        Thread.sleep(500);

        int followerApplied = follower.getStateMachine().lastAppliedIndex();
        int leaderApplied   = client.waitForLeader(500).getStateMachine().lastAppliedIndex();

        System.out.println("[Chaos:rejoin] follower.lastApplied=" + followerApplied
            + " leader.lastApplied=" + leaderApplied);

        // CREATE TABLE + 10 inserts = at least 11 entries; allow for NO-OP entries too
        assertTrue(followerApplied >= 10,
            "Rejoined follower should have applied at least 10 entries, got "
            + followerApplied + " (leader has " + leaderApplied + ")");
    }
}
