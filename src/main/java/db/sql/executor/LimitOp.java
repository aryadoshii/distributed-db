package db.sql.executor;

public class LimitOp implements Operator {

    private final Operator child;
    private final int limit;
    private int count;

    public LimitOp(Operator child, int limit) {
        this.child = child;
        this.limit = limit;
    }

    @Override
    public void open() {
        child.open();
        count = 0;
    }

    @Override
    public Row next() {
        if (count >= limit) return null;
        Row row = child.next();
        if (row != null) count++;
        return row;
    }

    @Override public void close() { child.close(); }
}
