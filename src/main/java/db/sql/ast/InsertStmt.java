package db.sql.ast;

import java.util.List;

/**
 * Represents an INSERT statement.
 *
 * INSERT INTO table (col1, col2, ...) VALUES (val1, val2, ...)
 *
 * columns: the named columns being inserted into (in order)
 * values:  the corresponding value expressions (must match columns length)
 */
public record InsertStmt(
    String table,
    List<String> columns,
    List<Expr> values
) implements Statement {

    public InsertStmt {
        if (columns.size() != values.size()) {
            throw new IllegalArgumentException(
                "Column count (" + columns.size() +
                ") does not match value count (" + values.size() + ")"
            );
        }
    }
}