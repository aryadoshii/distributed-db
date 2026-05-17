package db.sql.executor;

import db.server.Catalog;
import db.sql.ast.ColumnDef;
import db.sql.ast.Expr;
import db.storage.btree.BTree;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

public class InsertOp implements Operator {

    private final Catalog.TableInfo tableInfo;
    private final Map<String, Expr> values;
    private final BTree bTree;
    private final ExprEvaluator evaluator;
    private boolean executed;

    public InsertOp(Catalog.TableInfo tableInfo, Map<String, Expr> values, BTree bTree) {
        this.tableInfo = tableInfo;
        this.values    = values;
        this.bTree     = bTree;
        this.evaluator = new ExprEvaluator();
    }

    @Override public void open()  { executed = false; }
    @Override public void close() {}

    @Override
    public Row next() {
        if (executed) return null;
        executed = true;

        try {
            String pkCol = tableInfo.primaryKeyColumn;
            if (pkCol == null) throw new IllegalStateException(
                "Table " + tableInfo.tableName + " has no primary key");

            Expr pkExpr = values.get(pkCol);
            if (pkExpr == null) throw new IllegalStateException(
                "INSERT missing value for primary key column: " + pkCol);

            int pkValue = (Integer) evaluator.evaluate(pkExpr, new Row());
            bTree.insert(pkValue, serializeNonPkColumns());

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

            Expr expr  = values.get(col.name());
            Object val = (expr != null) ? evaluator.evaluate(expr, emptyRow) : null;

            if (val == null) { out.write(1); out.write(0); continue; }

            out.write(0); // is_null = false
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
