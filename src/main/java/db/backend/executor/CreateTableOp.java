package db.sql.executor;

import db.server.Catalog;
import db.sql.ast.ColumnDef;
import db.storage.btree.BTree;
import db.storage.buffer.BufferPool;

import java.io.IOException;
import java.util.List;

public class CreateTableOp implements Operator {

    private final String          tableName;
    private final List<ColumnDef> columns;
    private final Catalog         catalog;
    private final BufferPool      bufferPool;
    private boolean executed;

    public CreateTableOp(String tableName, List<ColumnDef> columns,
                         Catalog catalog, BufferPool bufferPool) {
        this.tableName  = tableName;
        this.columns    = columns;
        this.catalog    = catalog;
        this.bufferPool = bufferPool;
    }

    @Override public void open()  { executed = false; }
    @Override public void close() {}

    @Override
    public Row next() {
        if (executed) return null;
        executed = true;

        if (catalog.tableExists(tableName))
            throw new IllegalStateException("Table already exists: " + tableName);

        try {
            BTree tree = new BTree(bufferPool);
            bufferPool.flushAll();
            catalog.registerTable(tableName, columns, tree.getRootPageId());

            Row result = new Row();
            result.put("table",  tableName);
            result.put("status", "created");
            return result;
        } catch (IOException e) {
            throw new RuntimeException("CREATE TABLE failed", e);
        }
    }
}
