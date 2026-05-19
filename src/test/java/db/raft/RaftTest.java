package db.raft;

import db.raft.rpc.AppendEntries;
import db.raft.rpc.RequestVote;
import db.raft.statemachine.StateMachine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.*;

class RaftTest {

    // All nodes created by each test — stopped in tearDown
    private final List<RaftNode> allNodes = new ArrayList<>();

    // Nodes marked stopped (skipped in transport)
    private final Set<Integer> stoppedIds = Collections.synchronizedSet(new HashSet<>());

    @AfterEach
    void tearDown() {
        for (RaftNode n : allNodes) {
            try { n.stop(); } catch (Exception ignored) {}
        }
        allNodes.clear();
        stoppedIds.clear();
    }

    // ---------------------------------------------------------------
    //  Minimal state machine — records applied commands, idempotent
    // ---------------------------------------------------------------

    private static class NoOpStateMachine implements StateMachine {
        private final AtomicInteger lastApplied = new AtomicInteger(0);
        private final List<String>  commands    =
            Collections.synchronizedList(new ArrayList<>());

        @Override
        public synchronized byte[] apply(int index, byte[] command) {
            if (index <= lastApplied.get()) return "already_applied".getBytes();
            if (command != null) commands.add(new String(command));
            lastApplied.set(index);
            return "ok".getBytes();
        }

        @Override
        public int lastAppliedIndex() { return lastApplied.get(); }

    }

    // ---------------------------------------------------------------
    //  Cluster builder — in-process transport, stopped-node-safe
    // ---------------------------------------------------------------

    /**
     * Build a cluster of `size` nodes wired with in-process synchronous transport.
     * Stopped nodes (tracked via stoppedIds) never receive RPCs — their futures
     * simply never complete, simulating a network partition.
     */
    private List<RaftNode> buildCluster(int size) {
        Map<Integer, RaftNode> nodeMap = new LinkedHashMap<>();

        for (int id = 1; id <= size; id++) {
            final int nodeId = id;
            List<Integer> peers = IntStream.rangeClosed(1, size)
                .filter(i -> i != nodeId)
                .boxed()
                .collect(toList());
            RaftNode node = new RaftNode(RaftConfig.forTest(nodeId, peers), new NoOpStateMachine());
            nodeMap.put(nodeId, node);
            allNodes.add(node);
        }

        for (RaftNode node : nodeMap.values()) {
            node.start(
                (peerId, req) -> routeRequestVote(nodeMap, peerId, req),
                (peerId, req) -> routeAppendEntries(nodeMap, peerId, req)
            );
        }

        return new ArrayList<>(nodeMap.values());
    }

    private CompletableFuture<RequestVote.Response> routeRequestVote(
            Map<Integer, RaftNode> nodeMap, int peerId, RequestVote.Request req) {
        if (stoppedIds.contains(peerId)) return new CompletableFuture<>();
        // supplyAsync decouples RPC execution from the calling thread's lock,
        // preventing cross-node deadlocks when both nodes fire elections simultaneously
        return CompletableFuture.supplyAsync(() ->
            nodeMap.get(peerId).handleRequestVote(req));
    }

    private CompletableFuture<AppendEntries.Response> routeAppendEntries(
            Map<Integer, RaftNode> nodeMap, int peerId, AppendEntries.Request req) {
        if (stoppedIds.contains(peerId)) return new CompletableFuture<>();
        return CompletableFuture.supplyAsync(() ->
            nodeMap.get(peerId).handleAppendEntries(req));
    }

    private void stopNode(RaftNode node) {
        stoppedIds.add(node.getNodeId());
        node.stop();
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    /** Poll until exactly one leader exists among active nodes, or timeout. */
    private RaftNode waitForLeader(List<RaftNode> nodes, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<RaftNode> leaders = nodes.stream()
                .filter(n -> !stoppedIds.contains(n.getNodeId()) && n.isLeader())
                .collect(toList());
            if (leaders.size() == 1) return leaders.get(0);
            Thread.sleep(10);
        }
        return null;
    }

    /** Poll until condition is true or timeout expires. */
    private boolean waitFor(long timeoutMs, java.util.function.BooleanSupplier cond)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(10);
        }
        return cond.getAsBoolean();
    }

    // ---------------------------------------------------------------
    //  Leader election
    // ---------------------------------------------------------------

    @Test
    void singleNodeBecomesLeaderImmediately() throws InterruptedException {
        List<RaftNode> cluster = buildCluster(1);

        RaftNode leader = waitForLeader(cluster, 500);

        assertNotNull(leader, "Single-node cluster must elect a leader within 500ms");
        assertTrue(leader.isLeader());
    }

    @Test
    void threeNodeClusterElectsExactlyOneLeader() throws InterruptedException {
        List<RaftNode> cluster = buildCluster(3);

        RaftNode leader = waitForLeader(cluster, 500);

        assertNotNull(leader, "3-node cluster must elect a leader within 500ms");

        long leaderCount = cluster.stream().filter(RaftNode::isLeader).count();
        assertEquals(1, leaderCount, "Exactly one node must be leader");
    }

    @Test
    void electedLeaderHasTermAtLeastOne() throws InterruptedException {
        List<RaftNode> cluster = buildCluster(3);

        RaftNode leader = waitForLeader(cluster, 500);

        assertNotNull(leader);
        assertTrue(leader.getCurrentTerm() >= 1,
            "Leader term must be >= 1, was: " + leader.getCurrentTerm());
    }

    // ---------------------------------------------------------------
    //  Log replication
    // ---------------------------------------------------------------

    @Test
    void submitCompletesSuccessfully() throws Exception {
        List<RaftNode> cluster = buildCluster(3);
        RaftNode leader = waitForLeader(cluster, 500);
        assertNotNull(leader);

        byte[] result = leader.submit("INSERT id=1".getBytes())
            .get(2, TimeUnit.SECONDS);

        assertNotNull(result);
    }

    @Test
    void allNodesHaveCommitIndexAfterSubmit() throws Exception {
        List<RaftNode> cluster = buildCluster(3);
        RaftNode leader = waitForLeader(cluster, 500);
        assertNotNull(leader);

        leader.submit("INSERT id=1".getBytes()).get(2, TimeUnit.SECONDS);

        // Wait up to 200ms for followers to receive the updated commitIndex
        // via the next heartbeat
        boolean allCommitted = waitFor(200, () ->
            cluster.stream().allMatch(n -> n.getCommitIndex() >= 1)
        );

        assertTrue(allCommitted,
            "All nodes must have commitIndex >= 1 after submit. Actual: " +
            cluster.stream().map(n -> n.getNodeId() + "→" + n.getCommitIndex())
                   .collect(toList()));
    }

    @Test
    void allNodesHaveSameLogEntryAtIndexOne() throws Exception {
        List<RaftNode> cluster = buildCluster(3);
        RaftNode leader = waitForLeader(cluster, 500);
        assertNotNull(leader);

        leader.submit("INSERT id=1".getBytes()).get(2, TimeUnit.SECONDS);

        // Wait for all nodes to have at least index 1 in their logs
        waitFor(200, () -> cluster.stream().allMatch(n -> n.getLog().lastIndex() >= 1));

        // All nodes must agree on the entry at index 1 (the leader NO-OP)
        LogEntry referenceEntry = leader.getLog().get(1);
        for (RaftNode node : cluster) {
            LogEntry entry = node.getLog().get(1);
            assertEquals(referenceEntry.getIndex(), entry.getIndex(),
                "Node " + node.getNodeId() + " index mismatch at log[1]");
            assertEquals(referenceEntry.getTerm(), entry.getTerm(),
                "Node " + node.getNodeId() + " term mismatch at log[1]");
            assertEquals(referenceEntry.isNoOp(), entry.isNoOp(),
                "Node " + node.getNodeId() + " isNoOp mismatch at log[1]");
        }
    }

    // ---------------------------------------------------------------
    //  Leader failover
    // ---------------------------------------------------------------

    @Test
    void remainingNodesElectNewLeaderAfterOldLeaderStopped() throws Exception {
        List<RaftNode> cluster = buildCluster(3);
        RaftNode oldLeader = waitForLeader(cluster, 500);
        assertNotNull(oldLeader, "Initial leader must be elected");

        int oldTerm = oldLeader.getCurrentTerm();

        // Stop the leader — its heartbeats cease; followers' timers will fire
        stopNode(oldLeader);

        List<RaftNode> remaining = cluster.stream()
            .filter(n -> n.getNodeId() != oldLeader.getNodeId())
            .collect(toList());

        RaftNode newLeader = waitForLeader(remaining, 1000);

        assertNotNull(newLeader, "Remaining nodes must elect a new leader within 500ms");
        assertTrue(newLeader.getCurrentTerm() > oldTerm,
            "New leader term " + newLeader.getCurrentTerm() +
            " must exceed old leader term " + oldTerm);
    }

    // ---------------------------------------------------------------
    //  Safety — sequential commits, all nodes converge identically
    // ---------------------------------------------------------------

    @Test
    void fiveSequentialCommandsAllCommittedAndIdentical() throws Exception {
        List<RaftNode> cluster = buildCluster(3);
        RaftNode leader = waitForLeader(cluster, 500);
        assertNotNull(leader);

        int numCommands = 5;
        for (int i = 1; i <= numCommands; i++) {
            leader.submit(("cmd" + i).getBytes()).get(2, TimeUnit.SECONDS);
        }

        int expectedCommitIndex = leader.getCommitIndex();

        // Wait up to 1s for all nodes to reach the leader's commit index
        boolean allConverged = waitFor(1000, () ->
            cluster.stream().allMatch(n -> n.getCommitIndex() >= expectedCommitIndex)
        );

        assertTrue(allConverged,
            "All nodes must converge to commitIndex=" + expectedCommitIndex +
            " within 1s. Actual: " + cluster.stream()
                .map(n -> n.getNodeId() + "→" + n.getCommitIndex()).collect(toList()));

        // Verify all nodes have identical log entries for every committed index
        for (int idx = 1; idx <= expectedCommitIndex; idx++) {
            LogEntry ref = leader.getLog().get(idx);
            final int fi = idx;
            for (RaftNode node : cluster) {
                LogEntry entry = node.getLog().get(fi);
                assertEquals(ref.getIndex(), entry.getIndex(),
                    "Node " + node.getNodeId() + " index field mismatch at log[" + fi + "]");
                assertEquals(ref.getTerm(), entry.getTerm(),
                    "Node " + node.getNodeId() + " term mismatch at log[" + fi + "]");
                assertEquals(ref.isNoOp(), entry.isNoOp(),
                    "Node " + node.getNodeId() + " isNoOp mismatch at log[" + fi + "]");
                if (!ref.isNoOp()) {
                    assertArrayEquals(ref.getCommand(), entry.getCommand(),
                        "Node " + node.getNodeId() + " command mismatch at log[" + fi + "]");
                }
            }
        }
    }
}
