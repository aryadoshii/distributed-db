package db.sql.executor;

import db.sql.ast.*;

public class ExprEvaluator {

    public Object evaluate(Expr expr, Row row) {
        return switch (expr) {
            case ColumnExpr  c -> row.get(c.name());
            case LiteralExpr l -> l.value();
            case BinaryExpr  b -> evaluateBinary(b, row);
        };
    }

    public boolean evaluatePredicate(Expr expr, Row row) {
        Object result = evaluate(expr, row);
        if (result instanceof Boolean b) return b;
        throw new IllegalStateException(
            "Expression did not evaluate to boolean: " + expr
        );
    }

    private Object evaluateBinary(BinaryExpr expr, Row row) {
        return switch (expr.op()) {
            case AND         -> evaluatePredicate(expr.left(), row)
                                && evaluatePredicate(expr.right(), row);
            case OR          -> evaluatePredicate(expr.left(), row)
                                || evaluatePredicate(expr.right(), row);
            case IS_NULL     -> evaluate(expr.left(), row) == null;
            case IS_NOT_NULL -> evaluate(expr.left(), row) != null;
            default          -> evaluateComparison(expr, row);
        };
    }

    private boolean evaluateComparison(BinaryExpr expr, Row row) {
        Object left  = evaluate(expr.left(),  row);
        Object right = evaluate(expr.right(), row);

        if (left == null || right == null) return false;

        if (left instanceof Integer l && right instanceof Integer r) {
            return switch (expr.op()) {
                case EQ  -> l.equals(r);
                case NEQ -> !l.equals(r);
                case LT  -> l < r;
                case LTE -> l <= r;
                case GT  -> l > r;
                case GTE -> l >= r;
                default  -> throw new IllegalStateException("Not a comparison: " + expr.op());
            };
        }

        if (left instanceof String l && right instanceof String r) {
            int cmp = l.compareTo(r);
            return switch (expr.op()) {
                case EQ  -> cmp == 0;
                case NEQ -> cmp != 0;
                case LT  -> cmp < 0;
                case LTE -> cmp <= 0;
                case GT  -> cmp > 0;
                case GTE -> cmp >= 0;
                default  -> throw new IllegalStateException("Not a comparison: " + expr.op());
            };
        }

        if (left instanceof Boolean l && right instanceof Boolean r) {
            return switch (expr.op()) {
                case EQ  -> l.equals(r);
                case NEQ -> !l.equals(r);
                default  -> throw new IllegalStateException(
                    "Cannot apply " + expr.op() + " to boolean values"
                );
            };
        }

        throw new IllegalStateException(
            "Type mismatch in comparison: " + left.getClass().getSimpleName()
            + " vs " + right.getClass().getSimpleName()
        );
    }
}
