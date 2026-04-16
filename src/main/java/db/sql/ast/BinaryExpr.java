package db.sql.ast;

/**
 * A binary operation between two expressions.
 *
 * Examples:
 *   id = 5          BinaryExpr(ColumnExpr("id"), EQ, LiteralExpr(5))
 *   age > 18        BinaryExpr(ColumnExpr("age"), GT, LiteralExpr(18))
 *   a = 1 AND b = 2 BinaryExpr(BinaryExpr(...), AND, BinaryExpr(...))
 */
public record BinaryExpr(Expr left, Op op, Expr right) implements Expr {

    public enum Op {
        // Comparison
        EQ, NEQ, LT, LTE, GT, GTE,
        // Logical
        AND, OR,
        // Arithmetic
        ADD, SUB, MUL, DIV,
        // Null check
        IS_NULL, IS_NOT_NULL
    }
}