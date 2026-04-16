package db.sql.ast;

/**
 * Sealed interface for all expression nodes in the AST.
 *
 * Expressions appear in WHERE clauses, SELECT column lists,
 * and INSERT values.
 *
 * Usage in executor:
 *   Object result = switch (expr) {
 *     case ColumnExpr  c -> row.get(c.name());
 *     case LiteralExpr l -> l.value();
 *     case BinaryExpr  b -> evaluate(b, row);
 *   };
 */
public sealed interface Expr
    permits BinaryExpr, ColumnExpr, LiteralExpr {
}