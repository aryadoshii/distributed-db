package db.sql.executor;

import java.util.List;

public class ProjectOp implements Operator {

    private final Operator child;
    private final List<String> columns;
    private final boolean isStar;

    public ProjectOp(Operator child, List<String> columns) {
        this.child  = child;
        this.columns = columns;
        this.isStar = columns.isEmpty();
    }

    @Override public void open()  { child.open(); }

    @Override
    public Row next() {
        Row row = child.next();
        if (row == null) return null;
        return isStar ? row : row.project(columns);
    }

    @Override public void close() { child.close(); }
}
