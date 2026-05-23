package db.sql.ast;

/**
 * Sealed interface for all top-level SQL statements.
 * The compiler enforces exhaustive handling in switch expressions.
 *
 * Usage in planner:
 *   switch (stmt) {
 *     case SelectStmt s      -> planSelect(s);
 *     case InsertStmt i      -> planInsert(i);
 *     case CreateTableStmt c -> planCreate(c);
 *   }
 */
public sealed interface Statement
    permits SelectStmt, InsertStmt, CreateTableStmt {
}