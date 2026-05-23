package db.sql.ast;

import java.util.List;

/**
 * Represents a SELECT statement.
 *
 * SELECT columns FROM table [WHERE where] [ORDER BY orderBy [ASC|DESC]] [LIMIT limit]
 *
 * columns: list of Expr — either ColumnExpr nodes or a single STAR (*)
 *          represented as an empty list meaning "all columns"
 * where:   nullable — null means no WHERE clause
 * orderBy: nullable — null means no ORDER BY
 * limit:   -1 means no LIMIT
 */
public record SelectStmt(
    List<Expr> columns,    // empty = SELECT *
    String table,
    Expr where,            // nullable
    String orderByColumn,  // nullable
    boolean ascending,     // true = ASC (default), false = DESC
    int limit              // -1 = no limit
) implements Statement {

    /** Convenience: does this query have a WHERE clause? */
    public boolean hasWhere()   { return where != null; }
    public boolean hasOrderBy() { return orderByColumn != null; }
    public boolean hasLimit()   { return limit >= 0; }
    public boolean isStar()     { return columns.isEmpty(); }
}