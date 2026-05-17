package db.sql.executor;

import db.server.Catalog;
import db.sql.ast.*;
import db.storage.btree.BTree;
import db.storage.buffer.BufferPool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Planner {

    private final Catalog    catalog;
    private final BufferPool bufferPool;
    private final Map<Integer, BTree> treeRegistry;

    public Planner(Catalog catalog, BufferPool bufferPool) {
        this.catalog      = catalog;
        this.bufferPool   = bufferPool;
        this.treeRegistry = new HashMap<>();
    }

    public Operator plan(Statement stmt) {
        return switch (stmt) {
            case SelectStmt      s -> planSelect(s);
            case InsertStmt      i -> planInsert(i);
            case CreateTableStmt c -> planCreate(c);
        };
    }

    private Operator planSelect(SelectStmt stmt) {
        Catalog.TableInfo info = requireTable(stmt.table());
        Operator op = new SeqScanOp(info, bufferPool, getTree(info));

        if (stmt.hasWhere())   op = new FilterOp(op, stmt.where());
        if (stmt.hasOrderBy()) op = new SortOp(op, stmt.orderByColumn(), stmt.ascending());
        if (stmt.hasLimit())   op = new LimitOp(op, stmt.limit());

        List<String> colNames = stmt.isStar()
            ? List.of()
            : stmt.columns().stream()
                .map(e -> ((ColumnExpr) e).name())
                .collect(Collectors.toList());

        return new ProjectOp(op, colNames);
    }

    private Operator planInsert(InsertStmt stmt) {
        Catalog.TableInfo info = requireTable(stmt.table());

        Map<String, Expr> valueMap = IntStream.range(0, stmt.columns().size())
            .boxed()
            .collect(Collectors.toMap(
                i -> stmt.columns().get(i),
                i -> stmt.values().get(i)
            ));

        return new InsertOp(info, valueMap, getTree(info));
    }

    private Operator planCreate(CreateTableStmt stmt) {
        return new CreateTableOp(stmt.tableName(), stmt.columns(), catalog, bufferPool);
    }

    private Catalog.TableInfo requireTable(String name) {
        Catalog.TableInfo info = catalog.getTable(name);
        if (info == null) throw new IllegalStateException("Table not found: " + name);
        return info;
    }

    private BTree getTree(Catalog.TableInfo info) {
        return treeRegistry.computeIfAbsent(info.rootPageId,
            id -> new BTree(bufferPool, id));
    }

    public void registerTree(int rootPageId, BTree tree) {
        treeRegistry.put(rootPageId, tree);
    }
}
