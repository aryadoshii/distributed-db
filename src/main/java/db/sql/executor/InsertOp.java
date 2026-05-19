package db.sql.executor;

import db.server.Catalog;
import db.sql.ast.ColumnDef;
import db.sql.ast.Expr;
import db.storage.btree.BTree;
import db.txn.Transaction;
import db.txn.lock.DeadlockException;
import db.txn.lock.LockTimeoutException;
import db.txn.mvcc.VersionChain;
import db.txn.mvcc.VersionChainMap;
import db.txn.mvcc.VersionedRow;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * Insert operator with write lock acquisition and version chain writes.
 *
 * Write protocol:
 *   1. Resolve primary key value
 *   2. Acquire write lock on this row key (blocks; throws on deadlock)
 *   3. Prepend a new uncommitted VersionedRow to the chain
 *   4. Record the VersionedRow in the transaction's write set
 *   5. Persist bytes to the B-tree for durability
 */
public class InsertOp implements Operator {

    private final Catalog.TableInfo tableInfo;
    private final Map<String, Expr> values;
    private final BTree             bTree;
    private final TransactionHolder holder;
    private final VersionChainMap   versionChainMap;
    private final ExprEvaluator     evaluator;
    private boolean                 executed;

    public InsertOp(Catalog.TableInfo tableInfo,
                    Map<String, Expr> values,
                    BTree bTree,
                    TransactionHolder holder,
                    VersionChainMap versionChainMap) {
        this.tableInfo       = tableInfo;
        this.values          = values;
        this.bTree           = bTree;
        this.holder          = holder;
        this.versionChainMap = versionChainMap;
        this.evaluator       = new ExprEvaluator();
    }

    @Override public void open()  { executed = false; }
    @Override public void close() {}

    @Override
    public Row next() {
        if (executed) return null;
        executed = true;

        Transaction txn = holder.get();

        try {
            String pkCol = tableInfo.primaryKeyColumn;
            if (pkCol == null) throw new IllegalStateException(
                "Table " + tableInfo.tableName + " has no primary key");

            Expr pkExpr = values.get(pkCol);
            if (pkExpr == null) throw new IllegalStateException(
                "INSERT missing value for primary key column: " + pkCol);

            int    pkValue    = (Integer) evaluator.evaluate(pkExpr, new Row());
            byte[] serialized = serializeNonPkColumns();

            // Acquire write lock — blocks if held, throws on deadlock/timeout
            try {
                txn.acquireWriteLock(pkValue);
            } catch (DeadlockException | LockTimeoutException e) {
                throw new RuntimeException("Insert failed: could not acquire lock on key " + pkValue, e);
            }

            // Prepend new uncommitted version
            VersionChain chain = versionChainMap.getOrCreate(pkValue);
            VersionedRow version = chain.prepend(txn.getTxnId(), serialized, false);
            txn.addToWriteSet(version);

            // Persist to B-tree for durability
            bTree.insert(pkValue, serialized);

            Row result = new Row();
            result.put("affected_rows", 1);
            return result;

        } catch (IOException e) {
            throw new RuntimeException("Insert failed", e);
        }
    }

    private byte[] serializeNonPkColumns() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Row emptyRow = new Row();

        for (ColumnDef col : tableInfo.columns) {
            if (col.primaryKey()) continue;

            Expr   expr = values.get(col.name());
            Object val  = (expr != null) ? evaluator.evaluate(expr, emptyRow) : null;

            if (val == null) { out.write(1); out.write(0); continue; }

            out.write(0);
            switch (col.type()) {
                case INT -> {
                    out.write(0);
                    int v = (Integer) val;
                    out.write(new byte[]{(byte)(v>>24),(byte)(v>>16),(byte)(v>>8),(byte)v});
                }
                case VARCHAR -> {
                    out.write(1);
                    byte[] chars = ((String) val).getBytes();
                    int len = chars.length;
                    out.write(new byte[]{(byte)(len>>24),(byte)(len>>16),(byte)(len>>8),(byte)len});
                    out.write(chars);
                }
                case BOOLEAN -> { out.write(2); out.write((Boolean) val ? 1 : 0); }
            }
        }
        return out.toByteArray();
    }
}
