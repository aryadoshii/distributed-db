package db.sql.executor;

import db.server.Catalog;
import db.sql.ast.Statement;
import db.sql.parser.Parser;
import db.storage.buffer.BufferPool;
import db.storage.page.DiskManager;
import db.txn.Transaction;
import db.txn.TransactionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Five MVCC snapshot-isolation correctness properties:
 *   1. Dirty read prevention     — uncommitted write invisible to concurrent txn
 *   2. Committed read visibility — committed write visible to later txn
 *   3. Snapshot isolation        — commit after snapshot-start is invisible to that snapshot
 *   4. Own write visibility      — txn sees its own uncommitted writes
 *   5. Sequential commits        — all previously committed rows visible to new txn
 */
class TxnIntegrationTest {

    @TempDir Path tempDir;

    private DiskManager        diskManager;
    private BufferPool         bufferPool;
    private Catalog            catalog;
    private TransactionManager txnManager;
    private Planner            planner;

    @BeforeEach
    void setUp() throws IOException {
        diskManager = new DiskManager(tempDir.resolve("txn-test.db"));
        bufferPool  = new BufferPool(64, diskManager);
        catalog     = new Catalog();
        txnManager  = new TransactionManager();
        planner     = new Planner(catalog, bufferPool, txnManager);
        execute("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(255), age INT)");
    }

    @AfterEach
    void tearDown() throws IOException {
        diskManager.close();
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    /** Run a full statement in its own transaction (open → drain → close). */
    private List<Row> execute(String sql) {
        Statement stmt = Parser.parseSQL(sql);
        Operator  op   = planner.plan(stmt);
        op.open();
        List<Row> rows = new ArrayList<>();
        Row row;
        while ((row = op.next()) != null) rows.add(row);
        op.close();
        return rows;
    }

    /**
     * Run a SELECT * FROM users using the given transaction's snapshot,
     * bypassing TxnOperator so the transaction lifecycle is controlled
     * by the caller.
     */
    private List<Row> selectWithTxn(Transaction txn) {
        TransactionHolder holder = new TransactionHolder();
        holder.set(txn);
        Catalog.TableInfo info = catalog.getTable("users");
        Operator scan = new SeqScanOp(
            info, bufferPool, info.rootPageId, holder, planner.getVersionChainMap()
        );
        Operator proj = new ProjectOp(scan, List.of());  // SELECT *
        proj.open();
        List<Row> rows = new ArrayList<>();
        Row row;
        while ((row = proj.next()) != null) rows.add(row);
        proj.close();
        return rows;
    }

    // ---------------------------------------------------------------
    //  Test 1 — Dirty read prevention
    // ---------------------------------------------------------------

    @Test
    void dirtyReadPrevention() {
        // Open the INSERT operator (txn A begins) but do NOT close it yet
        // (closing would commit — we want to leave it uncommitted).
        Operator insertOp = planner.plan(
            Parser.parseSQL("INSERT INTO users (id, name, age) VALUES (1, 'alice', 25)")
        );
        insertOp.open();    // txn A begins
        insertOp.next();    // INSERT executes — version written, NOT committed

        // txn B runs SELECT — txn A is still active, so its write is invisible
        List<Row> rows = execute("SELECT * FROM users");

        insertOp.close();   // cleanup: commits txn A (assertion already captured)

        assertEquals(0, rows.size(),
            "Dirty read: uncommitted write must not be visible to a concurrent transaction");
    }

    // ---------------------------------------------------------------
    //  Test 2 — Committed read visibility
    // ---------------------------------------------------------------

    @Test
    void committedReadVisibility() {
        execute("INSERT INTO users (id, name, age) VALUES (1, 'alice', 25)");

        List<Row> rows = execute("SELECT * FROM users");

        assertEquals(1, rows.size());
        assertEquals(1,       rows.get(0).get("id"));
        assertEquals("alice", rows.get(0).get("name"));
        assertEquals(25,      rows.get(0).get("age"));
    }

    // ---------------------------------------------------------------
    //  Test 3 — Snapshot isolation
    // ---------------------------------------------------------------

    @Test
    void snapshotIsolation() {
        // txn A begins — snapshot captures the current state (empty table)
        Transaction txnA = txnManager.begin();

        // txn B inserts id=99 and commits
        execute("INSERT INTO users (id, name, age) VALUES (99, 'z', 99)");

        // txn A SELECT with its own (earlier) snapshot — must NOT see id=99
        List<Row> rows = selectWithTxn(txnA);
        txnManager.commit(txnA);

        assertEquals(0, rows.size(),
            "Snapshot isolation: row committed after snapshot-start must be invisible");
    }

    // ---------------------------------------------------------------
    //  Test 4 — Own write visibility
    // ---------------------------------------------------------------

    @Test
    void ownWriteVisibility() {
        // Open INSERT for txn A (not yet committed)
        Operator insertOp = planner.plan(
            Parser.parseSQL("INSERT INTO users (id, name, age) VALUES (5, 'dave', 50)")
        );
        insertOp.open();  // txn A begins
        insertOp.next();  // INSERT executes — uncommitted version in chain

        // Extract txn A from the TxnOperator wrapper
        Transaction txnA = ((TxnOperator) insertOp).getCurrentTxn();

        // SELECT using the same transaction — must see the uncommitted own write
        List<Row> rows = selectWithTxn(txnA);

        insertOp.close();  // commits txn A

        assertEquals(1, rows.size(),
            "Own write: transaction must see its own uncommitted writes");
        assertEquals(5,      rows.get(0).get("id"));
        assertEquals("dave", rows.get(0).get("name"));
    }

    // ---------------------------------------------------------------
    //  Test 5 — Sequential commits all visible
    // ---------------------------------------------------------------

    @Test
    void sequentialCommitsAllVisible() {
        execute("INSERT INTO users (id, name, age) VALUES (1, 'alice', 10)");
        execute("INSERT INTO users (id, name, age) VALUES (2, 'bob',   20)");
        execute("INSERT INTO users (id, name, age) VALUES (3, 'carol', 30)");

        List<Row> rows = execute("SELECT * FROM users");

        assertEquals(3, rows.size());
        assertEquals(1, rows.get(0).get("id"));
        assertEquals(2, rows.get(1).get("id"));
        assertEquals(3, rows.get(2).get("id"));
    }
}
