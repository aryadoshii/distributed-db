package db.raft;

import db.raft.rpc.AppendEntries;
import db.raft.rpc.RequestVote;
import db.raft.statemachine.StateMachine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;

/**
 * The Raft consensus state machine.
 *
 * Implements leader election, log replication, and state machine apply.
 * All state is protected by a single ReentrantLock — Raft is designed
 * to be single-threaded with async I/O, not fine-grained concurrent.
 *
 * Persistent state (must survive crashes — written before responding):
 *   currentTerm — latest term this node has seen
 *   votedFor    — candidateId voted for in currentTerm (-1 if none)
 *   log         — the append-only log of entries
 *
 * Volatile state (recomputed after restart):
 *   commitIndex — highest log index known to be committed
 *   lastApplied — highest log index applied to state machine
 *   role        — FOLLOWER / CANDIDATE / LEADER
 *
 * Leader-only volatile state (reinitialized on election):
 *   nextIndex   — per peer: next log index to send
 *   matchIndex  — per peer: highest index known to be replicated
 */
public class RaftNode {

    // ---------------------------------------------------------------
    //  Configuration and dependencies
    // ---------------------------------------------------------------

    private final RaftConfig   config;
    private final StateMachine stateMachine;

    // RPC transport — injected so tests can use in-process transport
    // Function takes (peerId, request) and returns a future response
    private BiFunction<Integer, RequestVote.Request,
                       CompletableFuture<RequestVote.Response>>  sendRequestVote;
    private BiFunction<Integer, AppendEntries.Request,
                       CompletableFuture<AppendEntries.Response>> sendAppendEntries;

    // ---------------------------------------------------------------
    //  Persistent state
    // ---------------------------------------------------------------

    private int     currentTerm;
    private int     votedFor;       // -1 = not voted
    private final RaftLog log;

    // ---------------------------------------------------------------
    //  Volatile state
    // ---------------------------------------------------------------

    private volatile int      commitIndex;
    private volatile int      lastApplied;
    private volatile RaftRole role;
    private volatile int      currentLeaderId;  // -1 = unknown

    // Leader state (valid only when role == LEADER)
    private final Map<Integer, Integer> nextIndex;   // peerId → next index to send
    private final Map<Integer, Integer> matchIndex;  // peerId → highest replicated index

    // ---------------------------------------------------------------
    //  Concurrency
    // ---------------------------------------------------------------

    private final ReentrantLock          raftLock;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?>           electionTimer;
    private ScheduledFuture<?>           heartbeatTimer;

    // Apply loop runs on a separate thread
    private final ExecutorService        applyExecutor;

    // ---------------------------------------------------------------
    //  Constructor
    // ---------------------------------------------------------------

    public RaftNode(RaftConfig config, StateMachine stateMachine) {
        this.config       = config;
        this.stateMachine = stateMachine;
        this.log          = new RaftLog();
        this.raftLock     = new ReentrantLock();
        this.scheduler    = Executors.newScheduledThreadPool(2);
        this.applyExecutor = Executors.newSingleThreadExecutor();
        this.nextIndex    = new HashMap<>();
        this.matchIndex   = new HashMap<>();

        // Initial state — all nodes start as followers
        this.currentTerm    = 0;
        this.votedFor       = -1;
        this.commitIndex    = 0;
        this.lastApplied    = stateMachine.lastAppliedIndex();
        this.role           = RaftRole.FOLLOWER;
        this.currentLeaderId = -1;
    }

    // ---------------------------------------------------------------
    //  Startup
    // ---------------------------------------------------------------

    /**
     * Start the node — begins election timer.
     * Call after injecting RPC transport functions.
     */
    public void start(
        BiFunction<Integer, RequestVote.Request,
                   CompletableFuture<RequestVote.Response>>  requestVoteFn,
        BiFunction<Integer, AppendEntries.Request,
                   CompletableFuture<AppendEntries.Response>> appendEntriesFn
    ) {
        this.sendRequestVote   = requestVoteFn;
        this.sendAppendEntries = appendEntriesFn;
        resetElectionTimer();
    }

    public void stop() {
        cancelElectionTimer();
        cancelHeartbeatTimer();
        scheduler.shutdownNow();
        applyExecutor.shutdownNow();
    }

    // ---------------------------------------------------------------
    //  Client request — only leader handles this
    // ---------------------------------------------------------------

    /**
     * Submit a command to the Raft cluster.
     * Must be called on the leader. Returns a future that completes
     * when the entry is committed and applied to the state machine.
     *
     * @param command SQL bytes to replicate
     * @return CompletableFuture<byte[]> — the state machine result
     */
    public CompletableFuture<byte[]> submit(byte[] command) {
        CompletableFuture<byte[]> future = new CompletableFuture<>();

        raftLock.lock();
        try {
            if (role != RaftRole.LEADER) {
                future.completeExceptionally(new IllegalStateException(
                    "Not the leader. Current leader: " + currentLeaderId
                ));
                return future;
            }

            // Append to local log
            LogEntry entry = log.append(currentTerm, command);

            // Advance commit index immediately — handles single-node clusters
            // and the case where peers are already caught up from prior heartbeats
            advanceCommitIndex();

            // Replicate to peers and complete future when committed
            replicateAndCommit(entry.getIndex(), future);

        } finally {
            raftLock.unlock();
        }

        return future;
    }

    // ---------------------------------------------------------------
    //  RPC handlers — called when this node receives an RPC
    // ---------------------------------------------------------------

    /**
     * Handle an incoming RequestVote RPC.
     * Called by the RPC layer when a candidate requests our vote.
     */
    public RequestVote.Response handleRequestVote(RequestVote.Request req) {
        raftLock.lock();
        try {
            // Rule 1: if incoming term > ours, update and become follower
            if (req.term() > currentTerm) {
                stepDownToFollower(req.term());
            }

            // Reject if request is from an older term
            if (req.term() < currentTerm) {
                return new RequestVote.Response(currentTerm, false);
            }

            // Check if we've already voted for someone else this term
            boolean canVote = (votedFor == -1 || votedFor == req.candidateId());

            // Check log up-to-date: candidate must be at least as up-to-date as us
            boolean candidateLogOk = isCandidateLogUpToDate(
                req.lastLogTerm(), req.lastLogIndex()
            );

            if (canVote && candidateLogOk) {
                votedFor = req.candidateId();
                resetElectionTimer();  // don't start our own election
                return new RequestVote.Response(currentTerm, true);
            }

            return new RequestVote.Response(currentTerm, false);

        } finally {
            raftLock.unlock();
        }
    }

    /**
     * Handle an incoming AppendEntries RPC.
     * Called for both heartbeats (entries empty) and log replication.
     */
    public AppendEntries.Response handleAppendEntries(AppendEntries.Request req) {
        raftLock.lock();
        try {
            // Rule: if incoming term > ours, update and become follower
            if (req.term() > currentTerm) {
                stepDownToFollower(req.term());
            }

            // Reject if request is from a stale leader
            if (req.term() < currentTerm) {
                return new AppendEntries.Response(currentTerm, false, log.lastIndex());
            }

            // Valid leader — reset election timer
            currentLeaderId = req.leaderId();
            resetElectionTimer();

            // Consistency check: does our log contain prevLogIndex at prevLogTerm?
            if (!log.hasEntryAt(req.prevLogIndex(), req.prevLogTerm())) {
                return new AppendEntries.Response(currentTerm, false, log.lastIndex());
            }

            // Append new entries (handles conflicts via truncation)
            if (!req.entries().isEmpty()) {
                log.appendAll(req.entries());
            }

            // Update commit index
            if (req.leaderCommit() > commitIndex) {
                commitIndex = Math.min(req.leaderCommit(), log.lastIndex());
                triggerApply();
            }

            return new AppendEntries.Response(currentTerm, true, log.lastIndex());

        } finally {
            raftLock.unlock();
        }
    }

    // ---------------------------------------------------------------
    //  Leader election
    // ---------------------------------------------------------------

    /**
     * Election timeout fired — start an election.
     * Increment term, vote for self, request votes from peers.
     */
    private void startElection() {
        raftLock.lock();
        try {
            if (role == RaftRole.LEADER) return; // already leader, ignore

            currentTerm++;
            role     = RaftRole.CANDIDATE;
            votedFor = config.nodeId();  // vote for self

            int term         = currentTerm;
            int lastLogIndex = log.lastIndex();
            int lastLogTerm  = log.lastTerm();

            resetElectionTimer(); // restart timer in case this election fails

            // Vote count starts at 1 (our own vote)
            final int[] votes = {1};

            // Single-node fast path: already have majority without sending any RPCs
            if (votes[0] >= config.majority()) {
                becomeLeader();
                return;
            }

            RequestVote.Request req = new RequestVote.Request(
                term, config.nodeId(), lastLogIndex, lastLogTerm
            );

            // Send RequestVote to all peers in parallel
            for (int peerId : config.peerIds()) {
                sendRequestVote.apply(peerId, req).thenAccept(response -> {
                    raftLock.lock();
                    try {
                        // Ignore stale responses
                        if (role != RaftRole.CANDIDATE || currentTerm != term) return;

                        if (response.term() > currentTerm) {
                            stepDownToFollower(response.term());
                            return;
                        }

                        if (response.voteGranted()) {
                            votes[0]++;
                            if (votes[0] >= config.majority()) {
                                becomeLeader();
                            }
                        }
                    } finally {
                        raftLock.unlock();
                    }
                });
            }

        } finally {
            raftLock.unlock();
        }
    }

    // ---------------------------------------------------------------
    //  Leader actions
    // ---------------------------------------------------------------

    /**
     * Transition to leader.
     * Initialize nextIndex and matchIndex for all peers.
     * Send immediate heartbeat to assert leadership.
     */
    private void becomeLeader() {
        role           = RaftRole.LEADER;
        currentLeaderId = config.nodeId();

        // Initialize leader state
        for (int peerId : config.peerIds()) {
            nextIndex.put(peerId,  log.lastIndex() + 1);
            matchIndex.put(peerId, 0);
        }

        cancelElectionTimer();

        // Append a NO-OP entry — ensures any uncommitted entries from
        // previous terms get committed via the log matching rule
        log.append(currentTerm, null);

        // Send immediate heartbeat + start heartbeat loop
        sendHeartbeats();
        scheduleHeartbeats();
    }

    /**
     * Send AppendEntries to all peers (heartbeat or log replication).
     */
    private void sendHeartbeats() {
        if (role != RaftRole.LEADER) return;

        for (int peerId : config.peerIds()) {
            sendAppendEntriesToPeer(peerId);
        }
    }

    /**
     * Send AppendEntries to a specific peer.
     * Handles both heartbeat (no new entries) and replication (with entries).
     */
    private void sendAppendEntriesToPeer(int peerId) {
        int ni          = nextIndex.getOrDefault(peerId, 1);
        int prevIndex   = ni - 1;
        int prevTerm    = log.get(prevIndex).getTerm();
        List<LogEntry> entries = log.getEntriesAfter(prevIndex);

        AppendEntries.Request req = new AppendEntries.Request(
            currentTerm, config.nodeId(),
            prevIndex, prevTerm,
            entries, commitIndex
        );

        sendAppendEntries.apply(peerId, req).thenAccept(response -> {
            raftLock.lock();
            try {
                if (role != RaftRole.LEADER || currentTerm != req.term()) return;

                if (response.term() > currentTerm) {
                    stepDownToFollower(response.term());
                    return;
                }

                if (response.success()) {
                    // Update matchIndex and nextIndex for this peer
                    matchIndex.put(peerId, response.matchIndex());
                    nextIndex.put(peerId,  response.matchIndex() + 1);
                    advanceCommitIndex();
                } else {
                    // Follower rejected — back up nextIndex and retry
                    int current = nextIndex.getOrDefault(peerId, 1);
                    nextIndex.put(peerId, Math.max(1, current - 1));
                    // Retry on next heartbeat
                }
            } finally {
                raftLock.unlock();
            }
        });
    }

    /**
     * Check if any new entries can be committed.
     *
     * An entry at index N is committed if:
     *   - N > commitIndex
     *   - log[N].term == currentTerm  (only commit entries from current term)
     *   - A majority of matchIndex[peer] >= N
     *
     * The term check is the subtle Raft safety rule from §5.4.2:
     * a leader can only commit entries from its own term directly.
     * Older entries get committed indirectly when a current-term entry commits.
     */
    private void advanceCommitIndex() {
        int n = log.lastIndex();
        while (n > commitIndex) {
            if (log.get(n).getTerm() == currentTerm) {
                int replicatedCount = 1; // leader itself
                for (int mi : matchIndex.values()) {
                    if (mi >= n) replicatedCount++;
                }
                if (replicatedCount >= config.majority()) {
                    commitIndex = n;
                    triggerApply();
                    break;
                }
            }
            n--;
        }
    }

    // ---------------------------------------------------------------
    //  State machine apply
    // ---------------------------------------------------------------

    /**
     * Apply committed entries to the state machine.
     * Runs on the applyExecutor thread — never blocks the Raft lock.
     */
    private void triggerApply() {
        applyExecutor.submit(() -> {
            raftLock.lock();
            int upTo = commitIndex;
            int from = lastApplied + 1;
            raftLock.unlock();

            for (int i = from; i <= upTo; i++) {
                LogEntry entry;
                raftLock.lock();
                try { entry = log.get(i); }
                finally { raftLock.unlock(); }

                if (!entry.isNoOp()) {
                    stateMachine.apply(i, entry.getCommand());
                }

                raftLock.lock();
                try { lastApplied = i; }
                finally { raftLock.unlock(); }
            }
        });
    }

    /**
     * Replicate entry at logIndex and complete the future when committed.
     * Called after leader appends to local log.
     */
    private void replicateAndCommit(int logIndex,
                                    CompletableFuture<byte[]> future) {
        // Send to all peers immediately
        sendHeartbeats();

        // Poll for commit in the apply executor
        applyExecutor.submit(() -> {
            // Wait until commitIndex >= logIndex
            while (true) {
                raftLock.lock();
                int ci = commitIndex;
                raftLock.unlock();

                if (ci >= logIndex) {
                    byte[] result = stateMachine.apply(
                        logIndex, log.get(logIndex).getCommand()
                    );
                    future.complete(result);
                    return;
                }

                try { Thread.sleep(5); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    future.completeExceptionally(e);
                    return;
                }
            }
        });
    }

    // ---------------------------------------------------------------
    //  Role transitions
    // ---------------------------------------------------------------

    private void stepDownToFollower(int newTerm) {
        currentTerm = newTerm;
        role        = RaftRole.FOLLOWER;
        votedFor    = -1;
        cancelHeartbeatTimer();
        resetElectionTimer();
    }

    // ---------------------------------------------------------------
    //  Timer management
    // ---------------------------------------------------------------

    private void resetElectionTimer() {
        cancelElectionTimer();
        int delay = ThreadLocalRandom.current().nextInt(
            config.electionTimeoutMinMs(),
            config.electionTimeoutMaxMs()
        );
        try {
            electionTimer = scheduler.schedule(
                this::startElection, delay, TimeUnit.MILLISECONDS
            );
        } catch (RejectedExecutionException ignored) {
            // Scheduler shut down — node is stopped, safe to ignore
        }
    }

    private void cancelElectionTimer() {
        if (electionTimer != null && !electionTimer.isDone()) {
            electionTimer.cancel(false);
        }
    }

    private void scheduleHeartbeats() {
        cancelHeartbeatTimer();
        try {
            heartbeatTimer = scheduler.scheduleAtFixedRate(
                this::sendHeartbeats,
                config.heartbeatIntervalMs(),
                config.heartbeatIntervalMs(),
                TimeUnit.MILLISECONDS
            );
        } catch (RejectedExecutionException ignored) {
            // Scheduler shut down — node is stopped, safe to ignore
        }
    }

    private void cancelHeartbeatTimer() {
        if (heartbeatTimer != null && !heartbeatTimer.isDone()) {
            heartbeatTimer.cancel(false);
        }
    }

    // ---------------------------------------------------------------
    //  Utility
    // ---------------------------------------------------------------

    /**
     * Is the candidate's log at least as up-to-date as ours?
     * Higher lastLogTerm wins. Equal term: higher index wins.
     */
    private boolean isCandidateLogUpToDate(int candidateLastTerm,
                                           int candidateLastIndex) {
        int myLastTerm  = log.lastTerm();
        int myLastIndex = log.lastIndex();

        if (candidateLastTerm != myLastTerm) {
            return candidateLastTerm > myLastTerm;
        }
        return candidateLastIndex >= myLastIndex;
    }

    // ---------------------------------------------------------------
    //  Accessors (for tests and monitoring)
    // ---------------------------------------------------------------

    public RaftRole getRole()           { return role; }
    public int      getCurrentTerm()    { return currentTerm; }
    public int      getCommitIndex()    { return commitIndex; }
    public int      getLastApplied()    { return lastApplied; }
    public int      getNodeId()         { return config.nodeId(); }
    public int      getCurrentLeader()  { return currentLeaderId; }
    public RaftLog  getLog()            { return log; }
    public boolean  isLeader()          { return role == RaftRole.LEADER; }
}
