<div align="center">

# distributed-db

### A Production-Grade Distributed Relational Database Engine — Built From Scratch in Java 17

**No external database libraries. Every layer — storage, SQL, transactions, consensus — hand-rolled.**

<br>

[![Java](https://img.shields.io/badge/Java-17+-ED8B00?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Maven](https://img.shields.io/badge/Maven-3.8+-C71A36?style=flat-square&logo=apachemaven&logoColor=white)](https://maven.apache.org/)
[![JMH](https://img.shields.io/badge/JMH-Benchmarked-4CAF50?style=flat-square)](https://openjdk.org/projects/code-tools/jmh/)
[![Micrometer](https://img.shields.io/badge/Micrometer-Metrics-informational?style=flat-square)](https://micrometer.io/)
[![Tests](https://img.shields.io/badge/Tests-146%20passing-brightgreen?style=flat-square)]()
[![License](https://img.shields.io/badge/License-MIT-475569?style=flat-square)](LICENSE)

> A **6-phase, ground-up implementation** of a distributed RDBMS — the same internals that power PostgreSQL, TiDB, and CockroachDB — as a single coherent Java codebase, built layer by layer from raw disk I/O up to distributed Raft consensus.

</div>

---

## What This Is

Most engineers have used a database. Few have built one.

This project implements the complete internals of a distributed RDBMS from scratch — a hand-written B+ tree, write-ahead log, buffer pool, recursive-descent SQL parser, MVCC transaction manager, Raft consensus layer, and a JDBC-compatible driver. No Hibernate. No external storage libraries. No Raft frameworks.

```sql
SELECT name, age FROM users WHERE age > 18 ORDER BY name LIMIT 10;
```

This SQL runs against a real B+ tree on disk, through a hand-written recursive-descent parser, a Volcano-model executor, and an MVCC transaction manager — replicated across a 3-node Raft cluster — all implemented in this repository.

---

## Architecture

```
┌─────────────────────────────────────────────────┐
│                  Client Layer                   │
│         CLI shell · JDBC driver · Pool          │
├─────────────────────────────────────────────────┤
│                  SQL Engine                     │
│     Lexer → Parser → AST → Planner → Executor   │
├─────────────────────────────────────────────────┤
│             Transaction Manager                 │
│      MVCC · Snapshot isolation · 2PL locks      │
├─────────────────────────────────────────────────┤
│               Storage Engine                    │
│    Buffer pool · B+ tree · WAL · Page manager   │
├─────────────────────────────────────────────────┤
│              Raft Consensus                     │
│   Leader election · Log replication · Snapshots │
├─────────────────────────────────────────────────┤
│             Network Transport                   │
│       Netty RPC · Protobuf · Cluster topology   │
└─────────────────────────────────────────────────┘
```

Each layer depends only on the layer below it. The storage engine has no knowledge of SQL. The SQL engine has no knowledge of Raft. Clean vertical separation all the way down.

---

## What Was Built — All 6 Phases

### Phase 1 — Storage Engine

The foundation. Every byte of data written or read passes through here.

**Page Manager** (`db.database.page`)

The database treats the disk as a flat array of fixed-size 4 KB pages. Every table row, index node, and log record lives inside one page. `Page.java` wraps a raw `ByteBuffer` with a 16-byte header encoding the page ID, type, free-space pointer, and tuple count. `DiskManager.java` handles raw I/O using Java NIO's `FileChannel` — non-buffered, direct syscalls, with a write loop that guarantees all `PAGE_SIZE` bytes land on disk even if the OS writes fewer in one call.

```
Page layout (4096 bytes):
  [0..3]   page_id       (int)
  [4..7]   page_type     (int)  DATA | BTREE_LEAF | BTREE_INTERNAL | WAL
  [8..11]  free_ptr      (int)  offset where free space begins
  [12..15] tuple_count   (int)
  [16..]   data
```

**Buffer Pool** (`db.database.buffer`)

Reading from disk on every query would be catastrophically slow. The buffer pool keeps a fixed number of pages in memory and evicts the least-recently-used page when the pool is full. `LRUReplacer.java` uses a `LinkedHashSet` for O(1) insertion, removal, and LRU identification. The pin/unpin contract ensures a page being actively read by a query thread is never evicted mid-operation.

```java
Page page = bufferPool.fetchPage(pageId);   // pinned — safe to read/write
try {
    // ... work with page ...
} finally {
    bufferPool.unpinPage(pageId, dirty);    // release — now evictable
}
```

**B+ Tree** (`db.database.btree`)

The primary data structure for all table storage. Internal nodes store separator keys and child page pointers. Leaf nodes store key-value pairs and are linked via `next_leaf_id` pointers — enabling range scans that walk forward across leaf pages without climbing back up the tree. Node splits are handled in full: leaf splits, internal node splits, and cascading splits up to the root.

A real production correctness bug was caught during integration testing: the original `BTreeLeaf.insert()` shift loop read and wrote entries in-place using `entryOffset()`, which re-scans from the start of the page on every call. Writing a shorter entry at position `i-1` would corrupt the computed offset of the entry at `i`, producing a garbage `valLen` that blew past the page boundary. Fixed by materialising all entries-to-shift into arrays first, then writing left-to-right.

**Write-Ahead Log** (`db.database.wal`)

The golden rule: a page is never written to disk unless its WAL record has been flushed first. Each log record stores a Log Sequence Number, the transaction ID, the record type (`BEGIN | UPDATE | COMMIT | ABORT | CHECKPOINT`), and for `UPDATE` records, both the before-image and after-image of the modified bytes. Records are buffered in memory until `COMMIT`, which forces an `fsync` before acknowledging the transaction.

A subtle serialization bug was caught: `FIXED_HEADER_SIZE` was incorrectly counting its own 4-byte length prefix, inflating every record's stated size and causing `readNextRecord()` to consume 4 extra bytes from the next record. Fixed from 32 to 28.

---

### Phase 2 — SQL Engine

Takes a raw SQL string and returns rows from the storage engine. No external parser libraries. No ANTLR. Hand-written, top to bottom.

**Lexer** (`db.backend.lexer`)

Converts a SQL string into a flat list of typed tokens. Handles 30+ token types: keywords, identifiers, integer and string literals with escaped-quote handling `''`, all six comparison operators with maximal munch (`<=`, `>=`, `!=` each produce one token), punctuation, single-line comments, and line/column tracking for error messages.

```
"SELECT name FROM users WHERE id = 5"
 ────────────────────────────────────────────────────────
 SELECT  name    FROM  users    WHERE  id      =   5   EOF
 KW      IDENT   KW    IDENT   KW     IDENT   EQ  INT
 1:1     1:8     1:13  1:18    1:24   1:30    1:33 1:35
```

**AST Node Hierarchy** (`db.backend.ast`)

Java 17 sealed interfaces model the AST. `Statement` permits exactly three subtypes: `SelectStmt`, `InsertStmt`, `CreateTableStmt`. `Expr` permits `BinaryExpr`, `ColumnExpr`, `LiteralExpr`. All node types are records — immutable with compact constructors that validate invariants. The sealed hierarchy means the compiler enforces exhaustive handling at every switch site.

**Parser** (`db.backend.parser`)

Recursive-descent: one method per grammar rule. The token stream is consumed via a cursor with three primitives — `peek()`, `consume()`, and `expect()`. `expect()` throws `ParseException` with the exact line and column of the unexpected token.

```
parseStatement()   dispatches on first token
  parseSelect()    consumes SELECT, columns, FROM, table, [WHERE], [ORDER BY], [LIMIT]
    parseExpr()    handles AND / OR at the top level
      parseTerm()  handles comparison operators and IS NULL / IS NOT NULL
  parseInsert()    consumes INSERT INTO table (cols) VALUES (vals)
  parseCreate()    consumes CREATE TABLE table (col_def, ...)
```

**Executor — Volcano Model** (`db.backend.executor`)

Every operator implements `open() / next() / close()`. Execution is lazy — nothing touches disk until `next()` is called. The planner builds an operator tree from the AST; the caller drains it row by row.

```
SELECT name FROM users WHERE age > 18 ORDER BY name LIMIT 5

ProjectOp(["name"])
  └── LimitOp(5)
        └── SortOp("name", ASC)         ← blocking: materialises all rows
              └── FilterOp(age > 18)
                    └── SeqScanOp(users) ← walks B+ tree leaf linked list
```

---

### Phase 3 — Transaction Manager

**MVCC** (`db.txn.mvcc`)

Every row has a version chain — a linked list of `VersionedRow` objects, newest first. When a transaction reads a row, it applies its `Snapshot` to find the newest version that was committed before it started. Readers never block writers; writers never block readers.

```
Visibility rule (the entire MVCC contract):
  if writerTxnId == snapshot.txnId    → own write, always visible
  if writerTxnId >  snapshot.txnId    → future write, not visible
  if activeTxns.contains(writerTxnId) → in-flight write, not visible
  else                                → committed before us, visible
```

**Lock Manager** (`db.txn.lock`)

Writes use row-level write locks via `LockTable.java` — `ReentrantLock` + `Condition` for efficient parking and waking. Deadlock detection runs on every blocking acquire: `DeadlockDetector.java` builds a wait-for graph and runs DFS to find cycles, aborting the youngest transaction in the cycle.

**TxnOperator** (`db.backend.executor`)

A transparent wrapper that begins a transaction in `open()`, commits in `close()`, and aborts on any `DeadlockException` or `RuntimeException`. Individual operators receive a `TransactionHolder` — a late-binding reference filled by `TxnOperator.open()` and read lazily on the first `next()` call.

Five MVCC correctness properties verified by the test suite: dirty read prevention, committed read visibility, snapshot isolation, own-write visibility, and sequential commit consistency.

---

### Phase 4 — Raft Consensus

**Leader Election**

Every follower runs a randomized election timer (150–300ms). If no heartbeat arrives within the window, the follower increments its term, becomes a candidate, votes for itself, and sends `RequestVote` RPCs to all peers. The vote is granted only if the candidate's log is at least as up-to-date as the voter's — the log up-to-date check that guarantees leader completeness. First candidate to majority wins.

**Log Replication**

The leader appends each write to its local log and sends `AppendEntries` RPCs to followers in parallel. Each RPC includes a `prevLogIndex/prevLogTerm` consistency check — a follower rejects entries if its log doesn't match at the previous index, triggering the leader to back up `nextIndex` and retry. Once a majority acknowledges an entry, `commitIndex` advances and the entry is applied to the state machine.

**State Machine Apply**

Committed entries are applied in-order to `DatabaseStateMachine` — which parses the SQL, plans it, and executes it against the storage engine. Every node applies the same entries in the same order. Every node ends up with the same B-tree contents.

**Chaos-Tested**

- Sub-300ms leader re-election on node kill
- Zero committed-write loss across leader failures
- Follower log catch-up after rejoin via Raft repair loop (confirmed: index 12 after restart from index 5)
- 50/50 concurrent writes surviving a mid-stream follower failure

---

### Phase 5 — Distributed Layer + JDBC

**DBServer** routes writes through Raft and reads directly to the leader's local state machine. Followers throw `NotLeaderException` with the current leader's address — `ClusterClient` catches this and retries against the correct node, handling leader failover transparently.

**Request flow:**

```
Client SQL
    ↓
DBServer (any node)
    ↓ if follower → NotLeaderException → ClusterClient retries leader
DBServer (leader)
    ↓
RaftNode.submit(sql.getBytes())
    ↓
AppendEntries → majority ack → commitIndex advances
    ↓
DatabaseStateMachine.apply() → parse → plan → execute → B-tree
    ↓
Result returned to client
```

**JDBC Driver** — four classes implementing `java.sql.Driver`, `java.sql.Connection`, `java.sql.Statement`, and `java.sql.ResultSet`. Registered via Java's Service Provider Interface in `META-INF/services/java.sql.Driver` so `DriverManager.getConnection("jdbc:distributeddb://localhost:9000/mydb")` works with no `Class.forName()`.

---

### Phase 6 — Benchmarks, Chaos Tests, Observability

**JMH Benchmarks** — B-tree, WAL, and end-to-end SQL throughput with proper JVM warmup, dead code elimination prevention, and forked JVM measurement. Real numbers in the section below.

**Chaos Tests** — leader kill mid-replication, follower stop and rejoin, concurrent writes under node failure. Every test verifies the core safety property: no committed write is ever lost.

**Micrometer Metrics** — named counters and timers for every subsystem (`db.btree.ops`, `db.wal.records`, `db.txn.ops`, `db.raft.commits`, `db.sql.query.latency`, etc.) exposed at `/metrics` in Prometheus text format via a JDK `HttpServer`. No external infrastructure needed.

---

## Benchmark Results

Run with JMH 1.37 — 3 warmup iterations × 2s, 5 measurement iterations × 2s, 1 JVM fork.

| Operation | Throughput | Notes |
|---|---:|---|
| B-tree point lookup | **1,820,773 ops/s** | In-memory; 50K rows fit in 1024-page buffer pool |
| B-tree range scan / 100 | 25,249 ops/s | Leaf linked-list walk, I/O bound |
| B-tree insert | 18,724 ops/s | WAL write + page eviction included |
| B-tree range scan / 1000 | 2,556 ops/s | Linear with scan width, as expected |

Point lookup is the headline: 1.8M ops/sec because 50K pre-loaded rows fit entirely in the buffer pool — pure in-memory B-tree traversal after warmup. Insert throughput reflects the real cost of WAL `fsync` + page eviction to disk. Range scan scales linearly with scan width, as expected from the leaf linked-list design.

---

## Test Results

```
Suite                           Tests    Status
────────────────────────────────────────────────
DiskManagerTest                     5    ✓
LRUReplacerTest                     5    ✓
BufferPoolTest                      7    ✓
BTreeTest                           5    ✓
WALTest                             5    ✓
LexerTest                          46    ✓
ParserTest                         21    ✓
ExecutorIntegrationTest            10    ✓
TxnIntegrationTest                  5    ✓
TransactionManagerTest             19    ✓
RaftTest                            8    ✓
Phase5IntegrationTest               6    ✓
LeaderFailoverChaosTest             2    ✓
FollowerRejoinChaosTest             1    ✓
ConcurrentWritesChaosTest           1    ✓
────────────────────────────────────────────────
Total                             146    0 failures
```

Three real production correctness bugs caught and fixed by the test suite — not test-specific failures, but issues that would have caused silent data corruption or deadlocks under real workloads.

---

## Java Concepts Demonstrated

Every concept appears because the problem requires it — not as a demonstration exercise.

| Concept | Where |
|---|---|
| `java.nio.ByteBuffer`, `FileChannel` | `DiskManager`, `WAL` |
| `ReentrantReadWriteLock` | `BufferPool` |
| `StampedLock` (optimistic read) | `VersionChain` |
| `AtomicInteger`, `AtomicLong` | `DiskManager`, `WAL`, `TransactionManager` |
| `LinkedHashSet` for O(1) LRU | `LRUReplacer` |
| `AutoCloseable` + try-with-resources | `DiskManager`, `WAL`, `BufferPool` |
| Sealed interfaces + records (Java 17) | entire AST hierarchy |
| Pattern matching `switch` expressions | `Planner`, `ExprEvaluator`, `Lexer` |
| Recursive algorithms | B-tree split/merge, recursive-descent parser |
| Iterator pattern (Volcano model) | all executor operators |
| `Comparator.comparing()` with null handling | `SortOp` |
| `ConcurrentHashMap`, `CopyOnWriteArraySet` | `Catalog`, `Snapshot` |
| `ThreadLocal<Transaction>` | `TransactionManager` |
| `ScheduledExecutorService` | Raft election timers, heartbeat loop |
| `CompletableFuture` chaining | Raft RPC fan-out to peers |
| `ReentrantLock` + `Condition` | `LockTable` waiter/notify |
| DFS cycle detection on live graph | `DeadlockDetector` |
| `ByteArrayOutputStream` binary serialization | `InsertOp`, `WAL` |
| `java.sql.Driver` SPI + `ServiceLoader` | `DBDriver`, `META-INF/services` |
| `volatile` + Java memory model | `VersionedRow`, `RaftNode` |
| `ThreadLocalRandom` | Raft election timeout randomisation |
| `Optional` for null-safe returns | `LRUReplacer` |
| Bit manipulation for integer encoding | `BTreeNode`, `WAL` |

---

## Project Structure

```
distributed-db/
├── pom.xml
└── src/
    ├── bench/java/db/bench/
    │   ├── BTreeBench.java
    │   ├── SQLBench.java
    │   └── WALBench.java
    ├── main/
    │   ├── java/db/
    │   │   ├── backend/
    │   │   │   ├── ast/
    │   │   │   │   ├── Statement.java          sealed interface
    │   │   │   │   ├── SelectStmt.java         record
    │   │   │   │   ├── InsertStmt.java         record
    │   │   │   │   ├── CreateTableStmt.java    record
    │   │   │   │   ├── Expr.java               sealed interface
    │   │   │   │   ├── BinaryExpr.java         record
    │   │   │   │   ├── ColumnExpr.java         record
    │   │   │   │   ├── LiteralExpr.java        record
    │   │   │   │   └── ColumnDef.java          record
    │   │   │   ├── executor/
    │   │   │   │   ├── Operator.java           Volcano model interface
    │   │   │   │   ├── Row.java
    │   │   │   │   ├── ExprEvaluator.java      sealed pattern-match evaluator
    │   │   │   │   ├── SeqScanOp.java          MVCC snapshot reads
    │   │   │   │   ├── FilterOp.java
    │   │   │   │   ├── ProjectOp.java
    │   │   │   │   ├── LimitOp.java
    │   │   │   │   ├── SortOp.java             blocking materialise + sort
    │   │   │   │   ├── InsertOp.java           lock + version chain write
    │   │   │   │   ├── CreateTableOp.java
    │   │   │   │   ├── TransactionHolder.java  late-binding txn reference
    │   │   │   │   ├── TxnOperator.java        begin/commit/abort lifecycle
    │   │   │   │   └── Planner.java
    │   │   │   ├── lexer/
    │   │   │   │   ├── TokenType.java
    │   │   │   │   ├── Token.java
    │   │   │   │   └── Lexer.java
    │   │   │   └── parser/
    │   │   │       ├── ParseException.java
    │   │   │       └── Parser.java
    │   │   ├── client/
    │   │   │   ├── ClusterClient.java          retry + leader redirect
    │   │   │   └── jdbc/
    │   │   │       ├── DBDriver.java           java.sql.Driver SPI
    │   │   │       ├── DBConnection.java
    │   │   │       ├── DBStatement.java
    │   │   │       ├── DBResultSet.java
    │   │   │       └── DBResultSetMetaData.java
    │   │   ├── config/
    │   │   │   ├── Catalog.java
    │   │   │   ├── DBServer.java               per-node server
    │   │   │   ├── NodeAddress.java
    │   │   │   └── NotLeaderException.java
    │   │   ├── database/
    │   │   │   ├── btree/
    │   │   │   │   ├── BTreeNode.java
    │   │   │   │   ├── BTreeLeaf.java
    │   │   │   │   ├── BTreeInternal.java
    │   │   │   │   └── BTree.java
    │   │   │   ├── buffer/
    │   │   │   │   ├── BufferPool.java
    │   │   │   │   └── LRUReplacer.java
    │   │   │   ├── page/
    │   │   │   │   ├── DiskManager.java
    │   │   │   │   └── Page.java
    │   │   │   └── wal/
    │   │   │       ├── LogRecord.java
    │   │   │       ├── LogType.java
    │   │   │       └── WAL.java
    │   │   ├── network/
    │   │   │   ├── RaftRpcClient.java
    │   │   │   └── RaftRpcServer.java
    │   │   ├── raft/
    │   │   │   ├── RaftNode.java               full state machine
    │   │   │   ├── RaftRole.java               FOLLOWER | CANDIDATE | LEADER
    │   │   │   ├── RaftLog.java
    │   │   │   ├── RaftConfig.java
    │   │   │   ├── LogEntry.java
    │   │   │   ├── rpc/
    │   │   │   │   ├── RequestVote.java        request + response records
    │   │   │   │   └── AppendEntries.java      request + response records
    │   │   │   └── statemachine/
    │   │   │       ├── StateMachine.java
    │   │   │       └── DatabaseStateMachine.java
    │   │   ├── settings/
    │   │   │   ├── MetricsRegistry.java        Micrometer SimpleMeterRegistry
    │   │   │   ├── DBMetrics.java              named counters + timers
    │   │   │   └── MetricsServer.java          /metrics Prometheus endpoint
    │   │   └── txn/
    │   │       ├── Transaction.java
    │   │       ├── TransactionManager.java
    │   │       ├── TxnStatus.java
    │   │       ├── lock/
    │   │       │   ├── LockTable.java
    │   │       │   ├── LockManager.java
    │   │       │   ├── DeadlockDetector.java   DFS on wait-for graph
    │   │       │   ├── DeadlockException.java
    │   │       │   └── LockTimeoutException.java
    │   │       └── mvcc/
    │   │           ├── Snapshot.java           point-in-time visibility set
    │   │           ├── VersionChain.java       StampedLock + linked versions
    │   │           ├── VersionChainMap.java    rowKey → VersionChain registry
    │   │           └── VersionedRow.java       one version in the chain
    │   └── resources/META-INF/services/
    │       └── java.sql.Driver                 SPI registration
    └── test/java/db/
        ├── backend/
        │   ├── executor/
        │   │   ├── ExecutorIntegrationTest.java
        │   │   └── TxnIntegrationTest.java
        │   ├── lexer/LexerTest.java
        │   └── parser/ParserTest.java
        ├── chaos/
        │   ├── Phase5ClusterBuilder.java
        │   ├── LeaderFailoverChaosTest.java
        │   ├── FollowerRejoinChaosTest.java
        │   └── ConcurrentWritesChaosTest.java
        ├── config/Phase5IntegrationTest.java
        ├── database/
        │   ├── btree/BTreeTest.java
        │   ├── buffer/BufferPoolTest.java
        │   ├── buffer/LRUReplacerTest.java
        │   ├── page/DiskManagerTest.java
        │   └── wal/WALTest.java
        ├── raft/RaftTest.java
        └── txn/TransactionManagerTest.java
```

---

## Build and Run

**Requirements:** Java 17+, Maven 3.8+

```bash
# Clone and build
git clone https://github.com/aryadoshii/distributed-db
cd distributed-db
mvn clean install

# Run all 146 tests
mvn test

# Run a specific suite
mvn test -Dtest=BTreeTest
mvn test -Dtest=RaftTest
mvn test -Dtest=LeaderFailoverChaosTest

# Run JMH benchmarks
mvn exec:java -Dexec.mainClass=db.bench.BTreeBench
mvn exec:java -Dexec.mainClass=db.bench.WALBench
mvn exec:java -Dexec.mainClass=db.bench.SQLBench
```

---

## Key Numbers

```
146 tests          0 failures
3 real bugs        caught and fixed by tests — not mocks
1,820,773 ops/s    B-tree point lookup (JMH verified)
sub-300ms          leader re-election after node kill
100/100 writes     survived leader failover in chaos test
50/50 writes       survived concurrent follower failure
~15,000 lines      of Java written from scratch
0                  external database libraries
```

---

<div align="center">

Built by **[Arya Doshi](https://github.com/aryadoshii)** · [linkedin.com/in/aryadoshii](https://linkedin.com/in/aryadoshii)

B.Tech Electronics & Telecommunications · Vishwakarma Institute of Technology, Pune

</div>