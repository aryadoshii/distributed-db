package db.server;

import db.raft.RaftNode;
import db.raft.rpc.AppendEntries;
import db.raft.rpc.RequestVote;
import db.raft.statemachine.DatabaseStateMachine;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;

/**
 * Per-node database server — owns the RaftNode, Planner, and StateMachine.
 *
 * Public API:
 *   execute(sql)  — runs a write SQL statement through Raft consensus
 *   query(sql)    — runs a read-only SQL query directly on the leader
 *                   without going through Raft (leader-local read)
 *
 * Write path:
 *   1. Check if this node is the leader
 *   2. If not: throw NotLeaderException with leader's address
 *   3. If yes: submit SQL bytes to RaftNode.submit()
 *   4. Block until the CompletableFuture completes (entry committed + applied)
 *   5. Return the result bytes from the state machine
 *
 * Read path:
 *   1. Check if this node is the leader
 *   2. If not: throw NotLeaderException
 *   3. If yes: execute SQL directly via Planner (bypass Raft)
 *   4. Return rows as a newline-delimited string
 */
public class DBServer {

    private static final int RPC_TIMEOUT_MS = 5_000;

    private final RaftNode             raftNode;
    private final DatabaseStateMachine stateMachine;
    private final Map<Integer, NodeAddress> clusterAddresses;
    private final NodeAddress          selfAddress;

    public DBServer(RaftNode             raftNode,
                    DatabaseStateMachine stateMachine,
                    NodeAddress          selfAddress,
                    Map<Integer, NodeAddress> clusterAddresses) {
        this.raftNode         = raftNode;
        this.stateMachine     = stateMachine;
        this.selfAddress      = selfAddress;
        this.clusterAddresses = clusterAddresses;
    }

    // ---------------------------------------------------------------
    //  Lifecycle
    // ---------------------------------------------------------------

    /**
     * Start the server. Wires the RPC transport and starts Raft timers.
     *
     * In production this would use Netty TCP. In tests this uses
     * in-process function references injected from the test harness.
     */
    public void start(
        BiFunction<Integer, RequestVote.Request,
                   CompletableFuture<RequestVote.Response>>  requestVoteFn,
        BiFunction<Integer, AppendEntries.Request,
                   CompletableFuture<AppendEntries.Response>> appendEntriesFn
    ) {
        raftNode.start(requestVoteFn, appendEntriesFn);
    }

    public void stop() {
        raftNode.stop();
    }

    // ---------------------------------------------------------------
    //  Write path — goes through Raft
    // ---------------------------------------------------------------

    /**
     * Execute a write SQL statement (INSERT, CREATE TABLE, UPDATE, DELETE).
     * Submits to Raft — all nodes will apply this command.
     *
     * @param sql the SQL to execute
     * @return result string from the state machine
     * @throws NotLeaderException   if this node is not the leader
     * @throws InterruptedException if the calling thread is interrupted
     * @throws TimeoutException     if consensus takes too long
     */
    public String execute(String sql)
            throws NotLeaderException, InterruptedException, TimeoutException {

        requireLeader();

        CompletableFuture<byte[]> future =
            raftNode.submit(sql.getBytes());

        try {
            byte[] result = future.get(RPC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return new String(result);
        } catch (ExecutionException e) {
            throw new RuntimeException("Execution failed: " + e.getCause().getMessage(),
                                       e.getCause());
        }
    }

    // ---------------------------------------------------------------
    //  Read path — leader local (bypass Raft)
    // ---------------------------------------------------------------

    /**
     * Execute a read-only SQL statement (SELECT).
     * Runs directly on the leader's local storage without Raft.
     *
     * Fast: no network round-trip. Consistent: leader has all committed data.
     *
     * @param sql the SELECT statement to run
     * @return newline-delimited row strings
     * @throws NotLeaderException if this node is not the leader
     */
    public String query(String sql) throws NotLeaderException {
        requireLeader();
        return stateMachine.executeQuery(sql);
    }

    // ---------------------------------------------------------------
    //  RPC delegation — called by the network layer
    // ---------------------------------------------------------------

    public RequestVote.Response handleRequestVote(RequestVote.Request req) {
        return raftNode.handleRequestVote(req);
    }

    public AppendEntries.Response handleAppendEntries(AppendEntries.Request req) {
        return raftNode.handleAppendEntries(req);
    }

    // ---------------------------------------------------------------
    //  Accessors
    // ---------------------------------------------------------------

    public boolean isLeader()                      { return raftNode.isLeader(); }
    public int     getNodeId()                     { return raftNode.getNodeId(); }
    public int     getCurrentTerm()                { return raftNode.getCurrentTerm(); }
    public RaftNode getRaftNode()                  { return raftNode; }
    public NodeAddress getSelfAddress()            { return selfAddress; }
    public DatabaseStateMachine getStateMachine()  { return stateMachine; }

    // ---------------------------------------------------------------
    //  Internal
    // ---------------------------------------------------------------

    private void requireLeader() throws NotLeaderException {
        if (!raftNode.isLeader()) {
            int    leaderId      = raftNode.getCurrentLeader();
            String leaderAddress = leaderId >= 0
                ? clusterAddresses.getOrDefault(leaderId, selfAddress).hostPort()
                : "unknown";
            throw new NotLeaderException(leaderId, leaderAddress);
        }
    }
}
