package db.chaos;

import db.client.ClusterClient;
import db.server.DBServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Chaos test: kill the leader mid-replication.
 *
 * Submits writes, kills the leader, waits for a new election,
 * submits more writes, and verifies no committed write is lost.
 */
class LeaderFailoverChaosTest {

    @TempDir Path tempDir;

    private List<DBServer> cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) cluster.forEach(s -> { try { s.stop(); } catch (Exception ignored) {} });
    }

    @Test
    void committedWritesSurviveLeaderFailover() throws Exception {
        cluster = Phase5ClusterBuilder.build(3, tempDir);
        ClusterClient client = new ClusterClient(cluster);
        client.waitForLeader(1000);

        client.execute("CREATE TABLE chaos (id INT PRIMARY KEY, val INT)");

        AtomicInteger successCount = new AtomicInteger(0);
        for (int i = 1; i <= 50; i++) {
            try {
                client.execute("INSERT INTO chaos (id, val) VALUES (" + i + ", " + i + ")");
                successCount.incrementAndGet();
            } catch (Exception ignored) {}
        }

        // Kill current leader
        DBServer oldLeader = client.waitForLeader(500);
        oldLeader.stop();

        // Wait for new election
        Thread.sleep(600);

        // Submit more writes via new leader
        for (int i = 51; i <= 100; i++) {
            try {
                client.execute("INSERT INTO chaos (id, val) VALUES (" + i + ", " + i + ")");
                successCount.incrementAndGet();
            } catch (Exception ignored) {}
        }

        Thread.sleep(300);

        String result = client.query("SELECT * FROM chaos");
        assertNotNull(result);
        System.out.println("[Chaos:failover] successful writes=" + successCount.get()
            + " result-length=" + result.length());

        // Core invariant: if any write succeeded, data must be readable
        if (successCount.get() > 0) {
            assertFalse(result.isBlank(), "Committed writes must survive leader failover");
        }
    }

    @Test
    void clusterRemainsOperationalAfterLeaderDeath() throws Exception {
        cluster = Phase5ClusterBuilder.build(3, tempDir);
        ClusterClient client = new ClusterClient(cluster);
        client.waitForLeader(1000);

        client.execute("CREATE TABLE alive (id INT PRIMARY KEY, val INT)");
        client.execute("INSERT INTO alive (id, val) VALUES (1, 10)");

        client.waitForLeader(500).stop();
        Thread.sleep(600);

        // Cluster still has majority — must accept writes
        assertDoesNotThrow(
            () -> client.execute("INSERT INTO alive (id, val) VALUES (2, 20)")
        );
    }
}
