package db.client;

import db.server.DBServer;
import db.server.NotLeaderException;

import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client-side cluster connection.
 *
 * Knows all nodes in the cluster. Tracks the current leader.
 * On NotLeaderException, updates the known leader and retries.
 * On timeout or no leader, retries against next node round-robin.
 *
 * In production this would talk to DBServer over TCP via Netty.
 * Here it holds direct references for testability — the same
 * pattern used in the Raft tests.
 *
 * Retry policy:
 *   - Up to maxRetries attempts total
 *   - Each attempt goes to the known leader (or round-robin if unknown)
 *   - On NotLeaderException: switch to the indicated leader, retry immediately
 *   - On timeout: try next node, wait 100ms, retry
 */
public class ClusterClient {

    private static final int MAX_RETRIES   = 10;
    private static final int RETRY_WAIT_MS = 100;

    private final List<DBServer>  nodes;
    private final AtomicInteger   knownLeaderIdx;

    public ClusterClient(List<DBServer> nodes) {
        this.nodes          = nodes;
        this.knownLeaderIdx = new AtomicInteger(0);
    }

    // ---------------------------------------------------------------
    //  Public API
    // ---------------------------------------------------------------

    /**
     * Execute a write SQL statement against the cluster.
     * Automatically redirects to leader and retries on failure.
     *
     * @param sql INSERT, CREATE TABLE, UPDATE, or DELETE
     * @return result string from the state machine
     * @throws RuntimeException if no leader is found after retries
     */
    public String execute(String sql) {
        return withRetry(sql, false);
    }

    /**
     * Execute a read-only SQL statement against the cluster leader.
     *
     * @param sql SELECT statement
     * @return result string from the state machine
     */
    public String query(String sql) {
        return withRetry(sql, true);
    }

    // ---------------------------------------------------------------
    //  Retry loop
    // ---------------------------------------------------------------

    private String withRetry(String sql, boolean isQuery) {
        int attempts = 0;

        while (attempts < MAX_RETRIES) {
            DBServer server = nodes.get(
                knownLeaderIdx.get() % nodes.size()
            );

            try {
                return isQuery
                    ? server.query(sql)
                    : server.execute(sql);

            } catch (NotLeaderException e) {
                // Server told us who the leader is — go there
                int leaderId = e.getLeaderId();
                if (leaderId >= 0 && leaderId < nodes.size()) {
                    knownLeaderIdx.set(leaderId);
                } else {
                    // Unknown leader — try next node
                    knownLeaderIdx.set(
                        (knownLeaderIdx.get() + 1) % nodes.size()
                    );
                }
                attempts++;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted", e);

            } catch (TimeoutException e) {
                // Try next node
                knownLeaderIdx.set(
                    (knownLeaderIdx.get() + 1) % nodes.size()
                );
                attempts++;
                sleepQuietly(RETRY_WAIT_MS);
            }
        }

        throw new RuntimeException(
            "Could not reach a leader after " + MAX_RETRIES + " attempts"
        );
    }

    // ---------------------------------------------------------------
    //  Leader discovery
    // ---------------------------------------------------------------

    /**
     * Find and cache the current leader.
     * Polls all nodes until one reports isLeader() == true.
     * Used by tests to wait for a leader after cluster startup.
     *
     * @param timeoutMs max time to wait
     * @return the leader DBServer
     * @throws RuntimeException if no leader elected within timeout
     */
    public DBServer waitForLeader(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (int i = 0; i < nodes.size(); i++) {
                if (nodes.get(i).isLeader()) {
                    knownLeaderIdx.set(i);
                    return nodes.get(i);
                }
            }
            sleepQuietly(20);
        }
        throw new RuntimeException(
            "No leader elected within " + timeoutMs + "ms"
        );
    }

    private void sleepQuietly(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
