package db.sql.executor;

import db.server.Catalog;
import db.sql.ast.Statement;
import db.sql.parser.Parser;
import db.storage.buffer.BufferPool;
import db.storage.page.DiskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExecutorIntegrationTest {

    @TempDir
    Path tempDir;

    private DiskManager diskManager;
    private BufferPool  bufferPool;
    private Catalog     catalog;
    private Planner     planner;

    @BeforeEach
    void setUp() throws IOException {
        diskManager = new DiskManager(tempDir.resolve("test.db"));
        bufferPool  = new BufferPool(64, diskManager);
        catalog     = new Catalog();
        planner     = new Planner(catalog, bufferPool);
    }

    @AfterEach
    void tearDown() throws IOException {
        diskManager.close();
    }

    // ---------------------------------------------------------------
    //  Helper
    // ---------------------------------------------------------------

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

    private static final String CREATE_USERS =
        "CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(255), age INT)";

    private void createUsersTable() { execute(CREATE_USERS); }

    // ---------------------------------------------------------------
    //  CREATE TABLE
    // ---------------------------------------------------------------

    @Test
    void createTableReturnsStatusCreated() {
        List<Row> result = execute(CREATE_USERS);

        assertEquals(1, result.size());
        assertEquals("created", result.get(0).get("status"));
        assertEquals("users",   result.get(0).get("table"));
    }

    @Test
    void createTableTwiceThrowsIllegalStateException() {
        execute(CREATE_USERS);

        assertThrows(IllegalStateException.class, () -> execute(CREATE_USERS));
    }

    // ---------------------------------------------------------------
    //  INSERT + SELECT *
    // ---------------------------------------------------------------

    @Test
    void insertAndSelectStar() {
        createUsersTable();
        execute("INSERT INTO users (id, name, age) VALUES (1, 'alice', 25)");
        execute("INSERT INTO users (id, name, age) VALUES (2, 'bob',   30)");
        execute("INSERT INTO users (id, name, age) VALUES (3, 'carol', 35)");

        List<Row> rows = execute("SELECT * FROM users");

        // B-tree returns rows in primary-key (id) order
        assertEquals(3, rows.size());

        assertEquals(1,       rows.get(0).get("id"));
        assertEquals("alice", rows.get(0).get("name"));
        assertEquals(25,      rows.get(0).get("age"));

        assertEquals(2,     rows.get(1).get("id"));
        assertEquals("bob", rows.get(1).get("name"));
        assertEquals(30,    rows.get(1).get("age"));

        assertEquals(3,       rows.get(2).get("id"));
        assertEquals("carol", rows.get(2).get("name"));
        assertEquals(35,      rows.get(2).get("age"));
    }

    // ---------------------------------------------------------------
    //  SELECT with WHERE
    // ---------------------------------------------------------------

    @Test
    void selectWhereEqReturnsExactlyOneRow() {
        createUsersTable();
        execute("INSERT INTO users (id, name, age) VALUES (1, 'alice', 10)");
        execute("INSERT INTO users (id, name, age) VALUES (2, 'bob',   20)");
        execute("INSERT INTO users (id, name, age) VALUES (3, 'carol', 30)");

        List<Row> rows = execute("SELECT * FROM users WHERE age = 20");

        assertEquals(1, rows.size());
        assertEquals(2,     rows.get(0).get("id"));
        assertEquals("bob", rows.get(0).get("name"));
        assertEquals(20,    rows.get(0).get("age"));
    }

    @Test
    void selectWhereGtReturnsTwoRows() {
        createUsersTable();
        execute("INSERT INTO users (id, name, age) VALUES (1, 'alice', 10)");
        execute("INSERT INTO users (id, name, age) VALUES (2, 'bob',   20)");
        execute("INSERT INTO users (id, name, age) VALUES (3, 'carol', 30)");

        List<Row> rows = execute("SELECT * FROM users WHERE age > 15");

        assertEquals(2, rows.size());
        assertEquals(20, rows.get(0).get("age"));
        assertEquals(30, rows.get(1).get("age"));
    }

    // ---------------------------------------------------------------
    //  SELECT with ORDER BY
    // ---------------------------------------------------------------

    @Test
    void selectOrderByIdAscReturnsAscendingIds() {
        createUsersTable();
        // Insert out of PK order to verify sort is applied
        execute("INSERT INTO users (id, name, age) VALUES (3, 'carol', 30)");
        execute("INSERT INTO users (id, name, age) VALUES (1, 'alice', 10)");
        execute("INSERT INTO users (id, name, age) VALUES (2, 'bob',   20)");

        List<Row> rows = execute("SELECT * FROM users ORDER BY id ASC");

        assertEquals(3, rows.size());
        assertEquals(1, rows.get(0).get("id"));
        assertEquals(2, rows.get(1).get("id"));
        assertEquals(3, rows.get(2).get("id"));
    }

    @Test
    void selectOrderByIdDescReturnsDescendingIds() {
        createUsersTable();
        execute("INSERT INTO users (id, name, age) VALUES (3, 'carol', 30)");
        execute("INSERT INTO users (id, name, age) VALUES (1, 'alice', 10)");
        execute("INSERT INTO users (id, name, age) VALUES (2, 'bob',   20)");

        List<Row> rows = execute("SELECT * FROM users ORDER BY id DESC");

        assertEquals(3, rows.size());
        assertEquals(3, rows.get(0).get("id"));
        assertEquals(2, rows.get(1).get("id"));
        assertEquals(1, rows.get(2).get("id"));
    }

    // ---------------------------------------------------------------
    //  SELECT with LIMIT
    // ---------------------------------------------------------------

    @Test
    void selectWithLimitReturnsExactlyNRows() {
        createUsersTable();
        for (int i = 1; i <= 5; i++) {
            execute("INSERT INTO users (id, name, age) VALUES ("
                + i + ", 'user" + i + "', " + (i * 10) + ")");
        }

        List<Row> rows = execute("SELECT * FROM users LIMIT 3");

        assertEquals(3, rows.size());
    }

    // ---------------------------------------------------------------
    //  SELECT with named columns
    // ---------------------------------------------------------------

    @Test
    void selectNamedColumnsExcludesUnrequestedColumns() {
        createUsersTable();
        execute("INSERT INTO users (id, name, age) VALUES (1, 'alice', 25)");

        List<Row> rows = execute("SELECT id, name FROM users");

        assertEquals(1, rows.size());
        Row row = rows.get(0);
        assertTrue(row.hasColumn("id"),    "Row should have 'id'");
        assertTrue(row.hasColumn("name"),  "Row should have 'name'");
        assertFalse(row.hasColumn("age"),  "Row should NOT have 'age'");
        assertEquals(1,       row.get("id"));
        assertEquals("alice", row.get("name"));
    }

    // ---------------------------------------------------------------
    //  IS NULL / IS NOT NULL
    // ---------------------------------------------------------------

    @Test
    void selectWhereIsNullAndIsNotNull() {
        createUsersTable();
        // Row with no name supplied — name serialises as null
        execute("INSERT INTO users (id, age) VALUES (1, 10)");
        execute("INSERT INTO users (id, name, age) VALUES (2, 'alice', 20)");

        List<Row> nullRows    = execute("SELECT * FROM users WHERE name IS NULL");
        List<Row> nonNullRows = execute("SELECT * FROM users WHERE name IS NOT NULL");

        assertEquals(1, nullRows.size());
        assertNull(nullRows.get(0).get("name"));
        assertEquals(1, nullRows.get(0).get("id"));

        assertEquals(1, nonNullRows.size());
        assertEquals("alice", nonNullRows.get(0).get("name"));
        assertEquals(2,       nonNullRows.get(0).get("id"));
    }
}
