package db.sql.ast;

/**
 * A column definition inside a CREATE TABLE statement.
 *
 * e.g.  id INT PRIMARY KEY
 *       name VARCHAR(255)
 */
public record ColumnDef(
    String name,
    DataType type,
    int typeParam,     // e.g. 255 for VARCHAR(255) — 0 if not applicable
    boolean primaryKey
) {
    public enum DataType { INT, VARCHAR, BOOLEAN }

    /** Shorthand for INT column */
    public static ColumnDef ofInt(String name, boolean pk) {
        return new ColumnDef(name, DataType.INT, 0, pk);
    }

    /** Shorthand for VARCHAR column */
    public static ColumnDef ofVarchar(String name, int length) {
        return new ColumnDef(name, DataType.VARCHAR, length, false);
    }
}
