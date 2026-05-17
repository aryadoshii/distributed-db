package db.sql.executor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class SortOp implements Operator {

    private final Operator child;
    private final String   sortColumn;
    private final boolean  ascending;

    private List<Row> sorted;
    private int cursor;

    public SortOp(Operator child, String sortColumn, boolean ascending) {
        this.child      = child;
        this.sortColumn = sortColumn;
        this.ascending  = ascending;
    }

    @Override
    public void open() {
        child.open();
        sorted = null;
        cursor = 0;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public Row next() {
        if (sorted == null) {
            sorted = new ArrayList<>();
            Row row;
            while ((row = child.next()) != null) sorted.add(row);

            Comparator<Row> cmp = Comparator.comparing(
                r -> (Comparable) r.get(sortColumn),
                Comparator.nullsLast(Comparator.naturalOrder())
            );
            if (!ascending) cmp = cmp.reversed();
            sorted.sort(cmp);
        }
        return cursor < sorted.size() ? sorted.get(cursor++) : null;
    }

    @Override
    public void close() {
        child.close();
        sorted = null;
    }
}
