package db.sql.executor;

import db.sql.ast.Expr;

public class FilterOp implements Operator {

    private final Operator child;
    private final Expr predicate;
    private final ExprEvaluator evaluator;

    public FilterOp(Operator child, Expr predicate) {
        this.child     = child;
        this.predicate = predicate;
        this.evaluator = new ExprEvaluator();
    }

    @Override public void open()  { child.open(); }

    @Override
    public Row next() {
        while (true) {
            Row row = child.next();
            if (row == null) return null;
            if (evaluator.evaluatePredicate(predicate, row)) return row;
        }
    }

    @Override public void close() { child.close(); }
}
