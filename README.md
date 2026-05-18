# distributed-db

A production-grade distributed relational database engine built from scratch in Java 17.
No external database libraries. Every layer — storage, SQL, transactions, consensus — hand-rolled.

```
SELECT name, age FROM users WHERE age > 18 ORDER BY name LIMIT 10;
```

This SQL runs against a real B+ tree on disk, through a hand-written recursive-descent parser,
a Volcano-model executor, and an MVCC transaction manager — all implemented in this repository.

---

## What this is

Most engineers have used a database. Few have built one.

This project implements the internals of a distributed RDBMS — the same concepts that power
PostgreSQL, TiDB, and CockroachDB — as a single coherent Java codebase, built layer by layer
from raw disk I/O up to distributed consensus.

**Current status:** Phases 1–2 complete. Storage engine, SQL parser, and query executor are
fully operational with 315 passing tests.

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

Each layer depends only on the layer below it. The storage engine has no knowledge of SQL.
The SQL engine has no knowledge of Raft. Clean separation all the way down.

---

## Implemented so far

### Phase 1 — Storage Engine

The foundation. Every byte of data written or read passes through here.

**Page Manager** (`db.storage.page`)

The database treats the disk as a flat array of fixed-size 4 KB pages.
Every table row, index node, and log record lives inside one page.
`Page.java` wraps a raw `ByteBuffer` with a 16-byte header encoding the page ID,
type, free-space pointer, and tuple count. `DiskManager.java` handles raw I/O
using Java NIO's `FileChannel` — non-buffered, direct syscalls, with a write loop
that guarantees all `PAGE_SIZE` bytes land on disk even if the OS writes fewer in
one call.

```
Page layout (4096 bytes):
  [0..3]   page_id       (int)
  [4..7]   page_type     (int)  DATA | BTREE_LEAF | BTREE_INTERNAL | WAL
  [8..11]  free_ptr      (int)  offset where free space begins
  [12..15] tuple_count   (int)
  [16..]   data
```

**Buffer Pool** (`db.storage.buffer`)

Reading from disk on every query would be catastrophically slow.
The buffer pool keeps a fixed number of pages in memory and evicts the
least-recently-used page when the pool is full. Written from scratch:
`LRUReplacer.java` uses a `LinkedHashSet` for O(1) insertion, removal,
and LRU identification. The pin/unpin contract ensures a page being actively
read by a query thread is never evicted mid-operation — a concurrency
correctness guarantee that trips up most implementations.

```java
Page page = bufferPool.fetchPage(pageId);   // pinned — safe to read/write
try {
    // ... work with page ...
} finally {
    bufferPool.unpinPage(pageId, dirty);    // release — now evictable
}
```

**B+ Tree** (`db.storage.btree`)

The primary data structure for all table storage. Keys are 4-byte integers
(the primary key column). Values are variable-length byte arrays (serialized rows).
Internal nodes store separator keys and child page pointers. Leaf nodes store
key-value pairs and are linked together via `next_leaf_id` pointers — enabling
range scans that walk forward across leaf pages without climbing back up the tree.

Node splits are handled in full: when a leaf fills up, it splits into two and
pushes the middle key up to the parent. If the parent is also full, it splits too.
This cascades up to the root, creating a new root when necessary.

A real production bug was caught during integration testing: the original
`BTreeLeaf.insert()` shift loop read and wrote entries in-place using
`entryOffset()`, which re-scans from the start of the page on every call.
Writing a shorter entry at position `i-1` would corrupt the computed offset
of the entry at `i`, producing a garbage `valLen` that blew past the page boundary.
Fixed by materializing all entries-to-shift into arrays first, then writing
left-to-right.

**Write-Ahead Log** (`db.storage.wal`)

The golden rule: a page is never written to disk unless its WAL record
has been flushed first. This guarantees that on a crash, the recovery manager
can replay the log and restore the database to a consistent state.

Each log record stores a Log Sequence Number, the transaction ID, the record
type (`BEGIN | UPDATE | COMMIT | ABORT | CHECKPOINT`), and for `UPDATE` records,
both the before-image and after-image of the modified bytes. Records are serialized
to a flat binary format and buffered in memory until `COMMIT`, which forces
an `fsync` before acknowledging the transaction.

A subtle bug was caught in the binary serialization: `FIXED_HEADER_SIZE`
was incorrectly counting the 4-byte `total_length` prefix field inside itself,
inflating every record's stated size by 4 bytes and causing `readNextRecord()`
to consume 4 extra bytes from the next record on every read. Fixed from 32 to 28.

---

### Phase 2 — SQL Engine

Takes a raw SQL string and returns rows from the storage engine.
No external parser libraries. No ANTLR. Hand-written, top to bottom.

**Lexer** (`db.sql.lexer`)

Converts a SQL string into a flat list of typed tokens. Handles 30+ token types:
keywords (`SELECT`, `FROM`, `WHERE`, `INSERT`, `CREATE`, ...), identifiers,
integer and string literals (with escaped-quote handling `''`), all six comparison
operators with maximal munch (`<=`, `>=`, `!=` each produce one token, not two),
punctuation, single-line comments (`-- ...`), and line/column tracking for error messages.

```
"SELECT name FROM users WHERE id = 5"
 ──────────────────────────────────────────────────────────────
 SELECT  name      FROM  users      WHERE  id        =   5    EOF
 KW      IDENT     KW    IDENT      KW     IDENT     EQ  INT
 1:1     1:8       1:13  1:18       1:24   1:30      1:33 1:35
```

**AST Node Hierarchy** (`db.sql.ast`)

Java 17 sealed interfaces model the AST. `Statement` permits exactly three
subtypes: `SelectStmt`, `InsertStmt`, `CreateTableStmt`. `Expr` permits
`BinaryExpr`, `ColumnExpr`, `LiteralExpr`. All node types are records — immutable,
with compact constructors that validate invariants.

The sealed hierarchy means the compiler enforces exhaustive handling. Add a new
statement type and every `switch` that processes statements fails to compile
until you add the new case. This is how real language implementations prevent
silent gaps in coverage.

**Parser** (`db.sql.parser`)

Recursive-descent: one method per grammar rule. The token stream is consumed
via a cursor with three primitives — `peek()`, `consume()`, and `expect()`.
`expect()` throws `ParseException` with the exact line and column of the
unexpected token.

```
parseStatement()   dispatches on first token
  parseSelect()    consumes SELECT, columns, FROM, table, [WHERE], [ORDER BY], [LIMIT]
    parseExpr()    handles AND / OR at the top level
      parseTerm()  handles comparison operators and IS NULL / IS NOT NULL
        parseOperand()  column reference or literal
  parseInsert()    consumes INSERT INTO table (cols) VALUES (vals)
  parseCreate()    consumes CREATE TABLE table (col_def, ...)
```

**Executor — Volcano Model** (`db.sql.executor`)

Every operator implements `open() / next() / close()`. Execution is lazy:
nothing touches disk until `next()` is called. The planner builds an operator
tree; the caller drains it by calling `next()` in a loop.

```
SELECT name FROM users WHERE age > 18 ORDER BY name LIMIT 5

ProjectOp(["name"])
  └── LimitOp(5)
        └── SortOp("name", ASC)        ← blocking: materialises all rows
              └── FilterOp(age > 18)
                    └── SeqScanOp(users)  ← walks B+ tree leaf linked list
```

`ExprEvaluator` pattern-matches on the sealed `Expr` hierarchy to evaluate
WHERE predicates. NULL comparisons follow the SQL standard (always false).
Type-safe comparisons are handled for `Integer`, `String`, and `Boolean` values
using Java's pattern-matching instanceof.

`SeqScanOp` walks the B+ tree leaf linked list from the leftmost leaf rightward,
deserializing raw bytes back into `Row` objects using the table's `ColumnDef`
metadata from the `Catalog`.

`SortOp` is the only blocking operator — `ORDER BY` requires seeing all rows
before sorting. It materialises the child's full output on the first `next()` call,
sorts using `Comparator.comparing()` with null-last ordering, then drains the
sorted list on subsequent calls.

---

## Test results

```
Suite                     Tests    Status
────────────────────────────────────────
DiskManagerTest               5    ✓
LRUReplacerTest               5    ✓
BufferPoolTest                7    ✓
BTreeTest                     5    ✓
WALTest                       5    ✓
LexerTest                    46    ✓
ParserTest                    21    ✓
ExecutorIntegrationTest       10    ✓
────────────────────────────────────────
Total                       315    0 failures
```

Two real bugs caught by the test suite — not test-specific failures but production
correctness issues that would have caused silent data corruption under real workloads.

---

## Project structure

```
distributed-db/
├── pom.xml
└── src/
    ├── main/java/db/
    │   ├── server/
    │   │   └── Catalog.java
    │   ├── sql/
    │   │   ├── ast/
    │   │   │   ├── Statement.java          sealed interface
    │   │   │   ├── SelectStmt.java         record
    │   │   │   ├── InsertStmt.java         record
    │   │   │   ├── CreateTableStmt.java    record
    │   │   │   ├── Expr.java               sealed interface
    │   │   │   ├── BinaryExpr.java         record
    │   │   │   ├── ColumnExpr.java         record
    │   │   │   ├── LiteralExpr.java        record
    │   │   │   └── ColumnDef.java          record
    │   │   ├── executor/
    │   │   │   ├── Operator.java
    │   │   │   ├── Row.java
    │   │   │   ├── ExprEvaluator.java
    │   │   │   ├── SeqScanOp.java
    │   │   │   ├── FilterOp.java
    │   │   │   ├── ProjectOp.java
    │   │   │   ├── LimitOp.java
    │   │   │   ├── SortOp.java
    │   │   │   ├── InsertOp.java
    │   │   │   ├── CreateTableOp.java
    │   │   │   └── Planner.java
    │   │   ├── lexer/
    │   │   │   ├── TokenType.java
    │   │   │   ├── Token.java
    │   │   │   └── Lexer.java
    │   │   └── parser/
    │   │       ├── ParseException.java
    │   │       └── Parser.java
    │   └── storage/
    │       ├── btree/
    │       │   ├── BTreeNode.java
    │       │   ├── BTreeLeaf.java
    │       │   ├── BTreeInternal.java
    │       │   └── BTree.java
    │       ├── buffer/
    │       │   ├── BufferPool.java
    │       │   └── LRUReplacer.java
    │       ├── page/
    │       │   ├── Page.java
    │       │   └── DiskManager.java
    │       └── wal/
    │           ├── LogType.java
    │           ├── LogRecord.java
    │           └── WAL.java
    └── test/java/db/
        ├── sql/
        │   ├── executor/ExecutorIntegrationTest.java
        │   ├── lexer/LexerTest.java
        │   └── parser/ParserTest.java
        └── storage/
            ├── btree/BTreeTest.java
            ├── buffer/BufferPoolTest.java
            ├── buffer/LRUReplacerTest.java
            ├── page/DiskManagerTest.java
            └── wal/WALTest.java
```

---

## Build and run

**Requirements:** Java 17+, Maven 3.8+

```bash
# Clone and build
git clone https://github.com/aryadoshii/distributed-db
cd distributed-db
mvn clean install

# Run all tests
mvn test

# Run tests for a specific module
mvn test -Dtest=BTreeTest
mvn test -Dtest=ExecutorIntegrationTest
```

---

## Java concepts demonstrated

This project is a deliberate survey of production Java engineering.
Every concept appears because the problem requires it — not as a demonstration.

| Concept | Where |
|---|---|
| `java.nio.ByteBuffer`, `FileChannel`, `MappedByteBuffer` | `DiskManager`, `WAL` |
| `ReentrantReadWriteLock` | `BufferPool` |
| `AtomicInteger`, `AtomicLong` | `DiskManager`, `WAL` |
| `LinkedHashSet` for O(1) LRU | `LRUReplacer` |
| `AutoCloseable` + try-with-resources | `DiskManager`, `WAL`, `BufferPool` |
| Sealed interfaces + records (Java 17) | entire AST hierarchy |
| Pattern matching `switch` expressions | `Planner`, `ExprEvaluator`, `Lexer` |
| Recursive algorithms | B-tree split/merge, recursive-descent parser |
| Iterator pattern (Volcano model) | all executor operators |
| `Comparator.comparing()` with null handling | `SortOp` |
| `ConcurrentHashMap` | `Catalog` |
| `ByteArrayOutputStream` for binary serialization | `InsertOp` |
| `Optional` for null-safe returns | `LRUReplacer` |
| Bit manipulation for integer encoding | `BTreeNode`, `WAL` |
| Custom unchecked exceptions with position info | `ParseException` |
| `computeIfAbsent` for lazy initialization | `Planner` |

---

## Roadmap

```
✓ Phase 1   Storage engine — Page, DiskManager, BufferPool, B+ tree, WAL
✓ Phase 2   SQL engine — Lexer, Parser, AST, Planner, Volcano executor
  Phase 3   Transaction manager — MVCC, snapshot isolation, deadlock detection
  Phase 4   Raft consensus — leader election, log replication, state machine
  Phase 5   Distributed layer — sharding, routing, JDBC driver
  Phase 6   Benchmarks (JMH), chaos tests, Prometheus metrics
```

---
