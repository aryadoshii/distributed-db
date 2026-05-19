package db.sql.executor;

import db.server.Catalog;
import db.sql.ast.*;
import db.storage.btree.BTree;
import db.storage.buffer.BufferPool;
import db.txn.TransactionManager;
import db.txn.mvcc.VersionChainMap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Converts a parsed AST Statement into a TxnOperator-wrapped execution plan.
 *
 * Every statement gets a TxnOperator that begins a transaction in open(),
 * commits in close(), and aborts on any exception. Inner operators receive
 * a TransactionHolder that TxnOperator fills at open() time.
 *
 * The VersionChainMap is shared for the database lifetime — it accumulates
 * version chains as rows are written.
 */
public class Planner {

    private final Catalog            catalog;
    private final BufferPool         bufferPool;
    private final TransactionManager txnManager;
    private final VersionChainMap    versionChainMap;
    private final Map<Integer, BTree> treeRegistry;

    public Planner(Catalog catalog,
                   BufferPool bufferPool,
                   TransactionManager txnManager) {
        this.catalog         = catalog;
        this.bufferPool      = bufferPool;
        this.txnManager      = txnManager;
        this.versionChainMap = new VersionChainMap();
        this.treeRegistry    = new HashMap<>();
    }

    public Operator plan(Statement stmt) {
        TransactionHolder holder = new TransactionHolder();

        Operator inner = switch (stmt) {
            case SelectStmt      s -> planSelect(s, holder);
            case InsertStmt      i -> planInsert(i, holder);
            case CreateTableStmt c -> planCreate(c);
        };

        return new TxnOperator(inner, txnManager, holder);
    }

    private Operator planSelect(SelectStmt stmt, TransactionHolder holder) {
        Catalog.TableInfo info = requireTable(stmt.table());

        Operator op = new SeqScanOp(
            info, bufferPool, info.rootPageId, holder, versionChainMap
        );

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

    private Operator planInsert(InsertStmt stmt, TransactionHolder holder) {
        Catalog.TableInfo info = requireTable(stmt.table());

        Map<String, Expr> valueMap = IntStream.range(0, stmt.columns().size())
            .boxed()
            .collect(Collectors.toMap(
                i -> stmt.columns().get(i),
                i -> stmt.values().get(i)
            ));

        return new InsertOp(info, valueMap, getTree(info), holder, versionChainMap);
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
        return treeRegistry.computeIfAbsent(info.rootPageId, id -> new BTree(bufferPool, id));
    }

    public void registerTree(int rootPageId, BTree tree) {
        treeRegistry.put(rootPageId, tree);
    }

    public VersionChainMap getVersionChainMap() { return versionChainMap; }
}
