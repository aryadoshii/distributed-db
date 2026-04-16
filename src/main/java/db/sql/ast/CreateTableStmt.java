package db.sql.ast;

import java.util.List;

/**
 * Represents a CREATE TABLE statement.
 *
 * CREATE TABLE table_name (
 *   col1 INT PRIMARY KEY,
 *   col2 VARCHAR(255),
 *   ...
 * )
 */
public record CreateTableStmt(
    String tableName,
    List<ColumnDef> columns
) implements Statement {

    /** Find the primary key column, or null if none declared */
    public ColumnDef primaryKey() {
        return columns.stream()
            .filter(ColumnDef::primaryKey)
            .findFirst()
            .orElse(null);
    }
}