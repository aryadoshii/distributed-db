package db.sql.ast;

/**
 * A reference to a column by name.
 * e.g. the "id" in:  WHERE id = 5
 *                     SELECT id, name FROM ...
 */
public record ColumnExpr(String name) implements Expr {
    public ColumnExpr {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Column name cannot be blank");
        }
    }
}