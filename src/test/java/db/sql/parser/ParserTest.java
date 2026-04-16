package db.sql.parser;

import db.sql.ast.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParserTest {

    private static Statement parse(String sql) {
        return Parser.parseSQL(sql);
    }

    // ---------------------------------------------------------------
    //  SELECT — basic
    // ---------------------------------------------------------------

    @Test
    void selectStarFromTable() {
        SelectStmt stmt = (SelectStmt) parse("SELECT * FROM users");

        assertTrue(stmt.isStar(), "isStar should be true for SELECT *");
        assertEquals("users", stmt.table());
        assertNull(stmt.where());
        assertFalse(stmt.hasOrderBy());
        assertFalse(stmt.hasLimit());
    }

    @Test
    void selectNamedColumns() {
        SelectStmt stmt = (SelectStmt) parse("SELECT id, name FROM users");

        assertFalse(stmt.isStar());
        assertEquals(2, stmt.columns().size());

        ColumnExpr first  = (ColumnExpr) stmt.columns().get(0);
        ColumnExpr second = (ColumnExpr) stmt.columns().get(1);
        assertEquals("id",   first.name());
        assertEquals("name", second.name());
        assertEquals("users", stmt.table());
    }

    // ---------------------------------------------------------------
    //  SELECT — WHERE
    // ---------------------------------------------------------------

    @Test
    void selectWithSimpleWhereEq() {
        SelectStmt stmt = (SelectStmt) parse("SELECT id FROM users WHERE id = 5");

        assertTrue(stmt.hasWhere());
        BinaryExpr where = (BinaryExpr) stmt.where();
        assertEquals(BinaryExpr.Op.EQ, where.op());

        ColumnExpr  col = (ColumnExpr)  where.left();
        LiteralExpr val = (LiteralExpr) where.right();
        assertEquals("id", col.name());
        assertEquals(5,    val.value());
    }

    @Test
    void selectWithAndCondition() {
        SelectStmt stmt = (SelectStmt) parse(
            "SELECT * FROM t WHERE age > 18 AND active = true"
        );

        BinaryExpr where = (BinaryExpr) stmt.where();
        assertEquals(BinaryExpr.Op.AND, where.op());

        BinaryExpr left  = (BinaryExpr) where.left();
        BinaryExpr right = (BinaryExpr) where.right();

        assertEquals(BinaryExpr.Op.GT, left.op());
        assertEquals("age", ((ColumnExpr) left.left()).name());
        assertEquals(18,    ((LiteralExpr) left.right()).value());

        assertEquals(BinaryExpr.Op.EQ, right.op());
        assertEquals("active", ((ColumnExpr) right.left()).name());
        assertEquals(true,     ((LiteralExpr) right.right()).value());
    }

    // ---------------------------------------------------------------
    //  SELECT — ORDER BY / LIMIT
    // ---------------------------------------------------------------

    @Test
    void selectWithOrderByDescAndLimit() {
        SelectStmt stmt = (SelectStmt) parse(
            "SELECT * FROM t ORDER BY id DESC LIMIT 10"
        );

        assertTrue(stmt.hasOrderBy());
        assertEquals("id", stmt.orderByColumn());
        assertFalse(stmt.ascending(), "DESC should set ascending=false");

        assertTrue(stmt.hasLimit());
        assertEquals(10, stmt.limit());
    }

    // ---------------------------------------------------------------
    //  SELECT — IS NULL / IS NOT NULL
    // ---------------------------------------------------------------

    @Test
    void selectWhereIsNull() {
        SelectStmt stmt = (SelectStmt) parse(
            "SELECT * FROM t WHERE name IS NULL"
        );

        BinaryExpr where = (BinaryExpr) stmt.where();
        assertEquals(BinaryExpr.Op.IS_NULL, where.op());
        assertEquals("name", ((ColumnExpr) where.left()).name());
        assertTrue(((LiteralExpr) where.right()).isNull());
    }

    @Test
    void selectWhereIsNotNull() {
        SelectStmt stmt = (SelectStmt) parse(
            "SELECT * FROM t WHERE name IS NOT NULL"
        );

        BinaryExpr where = (BinaryExpr) stmt.where();
        assertEquals(BinaryExpr.Op.IS_NOT_NULL, where.op());
        assertEquals("name", ((ColumnExpr) where.left()).name());
    }

    // ---------------------------------------------------------------
    //  INSERT
    // ---------------------------------------------------------------

    @Test
    void insertWithTwoColumnsAndValues() {
        InsertStmt stmt = (InsertStmt) parse(
            "INSERT INTO users (id, name) VALUES (1, 'alice')"
        );

        assertEquals("users", stmt.table());

        assertEquals(List.of("id", "name"), stmt.columns());

        assertEquals(2, stmt.values().size());
        LiteralExpr v0 = (LiteralExpr) stmt.values().get(0);
        LiteralExpr v1 = (LiteralExpr) stmt.values().get(1);
        assertEquals(1,       v0.value());
        assertEquals("alice", v1.value());
    }

    // ---------------------------------------------------------------
    //  CREATE TABLE
    // ---------------------------------------------------------------

    @Test
    void createTableWithPrimaryKeyAndVarchar() {
        CreateTableStmt stmt = (CreateTableStmt) parse(
            "CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(255))"
        );

        assertEquals("users", stmt.tableName());
        assertEquals(2, stmt.columns().size());

        ColumnDef id = stmt.columns().get(0);
        assertEquals("id",               id.name());
        assertEquals(ColumnDef.DataType.INT, id.type());
        assertTrue(id.primaryKey(),      "id column should be PRIMARY KEY");
        assertEquals(0, id.typeParam());

        ColumnDef name = stmt.columns().get(1);
        assertEquals("name",                  name.name());
        assertEquals(ColumnDef.DataType.VARCHAR, name.type());
        assertFalse(name.primaryKey());
        assertEquals(255, name.typeParam());
    }

    // ---------------------------------------------------------------
    //  ParseException cases
    // ---------------------------------------------------------------

    @Test
    void missingColumnListThrowsParseException() {
        // "SELECT FROM users" — parser sees FROM where it expects columns
        assertThrows(ParseException.class, () -> parse("SELECT FROM users"));
    }

    @Test
    void missingFromKeywordThrowsParseException() {
        // "SELECT id users" — no FROM between column and table name
        assertThrows(ParseException.class, () -> parse("SELECT id users"));
    }

    @Test
    void missingTableNameThrowsParseException() {
        // "SELECT id FROM" — FROM with no following identifier
        assertThrows(ParseException.class, () -> parse("SELECT id FROM"));
    }

    // ---------------------------------------------------------------
    //  Optional extras — trailing semicolon, ORDER BY ASC default
    // ---------------------------------------------------------------

    @Test
    void trailingSemicolonIsAccepted() {
        assertDoesNotThrow(() -> parse("SELECT * FROM t;"));
    }

    @Test
    void orderByAscIsDefault() {
        SelectStmt stmt = (SelectStmt) parse("SELECT * FROM t ORDER BY id");
        assertTrue(stmt.ascending(), "Default direction should be ASC");
    }

    @Test
    void orderByExplicitAsc() {
        SelectStmt stmt = (SelectStmt) parse("SELECT * FROM t ORDER BY id ASC");
        assertTrue(stmt.ascending());
    }

    @Test
    void selectWithOrCondition() {
        SelectStmt stmt = (SelectStmt) parse("SELECT * FROM t WHERE a = 1 OR b = 2");
        BinaryExpr where = (BinaryExpr) stmt.where();
        assertEquals(BinaryExpr.Op.OR, where.op());
    }

    @Test
    void selectSingleColumnNoWhere() {
        SelectStmt stmt = (SelectStmt) parse("SELECT age FROM employees");
        assertFalse(stmt.isStar());
        assertEquals(1, stmt.columns().size());
        assertEquals("age", ((ColumnExpr) stmt.columns().get(0)).name());
        assertEquals("employees", stmt.table());
        assertNull(stmt.where());
    }

    @Test
    void insertWithNullValue() {
        InsertStmt stmt = (InsertStmt) parse(
            "INSERT INTO t (col) VALUES (NULL)"
        );
        LiteralExpr val = (LiteralExpr) stmt.values().get(0);
        assertTrue(val.isNull());
    }

    @Test
    void createTableBooleanColumn() {
        CreateTableStmt stmt = (CreateTableStmt) parse(
            "CREATE TABLE flags (enabled BOOLEAN)"
        );
        ColumnDef col = stmt.columns().get(0);
        assertEquals("enabled", col.name());
        assertEquals(ColumnDef.DataType.BOOLEAN, col.type());
        assertFalse(col.primaryKey());
    }

    @Test
    void selectWithGteComparison() {
        SelectStmt stmt = (SelectStmt) parse("SELECT * FROM t WHERE score >= 90");
        BinaryExpr where = (BinaryExpr) stmt.where();
        assertEquals(BinaryExpr.Op.GTE, where.op());
        assertEquals(90, ((LiteralExpr) where.right()).value());
    }

    @Test
    void selectWithNeqComparison() {
        SelectStmt stmt = (SelectStmt) parse("SELECT * FROM t WHERE status != 0");
        BinaryExpr where = (BinaryExpr) stmt.where();
        assertEquals(BinaryExpr.Op.NEQ, where.op());
    }
}
