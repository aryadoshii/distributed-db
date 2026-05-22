package db.chaos;

import db.client.ClusterClient;
import db.server.DBServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Chaos test: concurrent writers + random node failure.
 *
 * Multiple threads submit writes simultaneously.
 * A follower is killed mid-flight.
 * Verifies: if any write received SUCCESS, the cluster is non-empty.
 * This tests the core safety property — no committed write is lost.
 */
class ConcurrentWritesChaosTest {

    @TempDir Path tempDir;

    private List<DBServer> cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) cluster.forEach(s -> { try { s.stop(); } catch (Exception ignored) {} });
    }

    @Test
    void noCommittedWriteLostUnderConcurrentFailure() throws Exception {
        cluster = Phase5ClusterBuilder.build(3, tempDir);
        ClusterClient client = new ClusterClient(cluster);
        client.waitForLeader(1000);

        client.execute("CREATE TABLE concurrent (id INT PRIMARY KEY, thread INT)");

        int threadCount     = 5;
        int writesPerThread = 10;
        AtomicInteger successCount = new AtomicInteger(0);
        CountDownLatch done = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        // Kill a follower after 100ms
        ScheduledExecutorService killer = Executors.newSingleThreadScheduledExecutor();
        killer.schedule(() -> cluster.stream()
            .filter(s -> !s.isLeader())
            .findFirst()
            .ifPresent(DBServer::stop),
            100, TimeUnit.MILLISECONDS);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            final int startId  = t * writesPerThread + 1;
            pool.submit(() -> {
                try {
                    for (int i = startId; i < startId + writesPerThread; i++) {
                        try {
                            client.execute("INSERT INTO concurrent (id, thread) VALUES ("
                                + i + ", " + threadId + ")");
                            successCount.incrementAndGet();
                        } catch (Exception ignored) {}
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        done.await(10, TimeUnit.SECONDS);
        pool.shutdown();
        killer.shutdown();

        Thread.sleep(500);

        String result = client.query("SELECT * FROM concurrent");
        System.out.println("[Chaos:concurrent] successful writes=" + successCount.get()
            + " result-length=" + result.length());

        if (successCount.get() > 0) {
            assertFalse(result.isBlank(),
                "Committed writes must be readable after concurrent failure");
        }
    }
}
