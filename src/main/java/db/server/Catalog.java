package db.server;

import db.sql.ast.ColumnDef;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Catalog {

    public static class TableInfo {
        public final String tableName;
        public final List<ColumnDef> columns;
        public final int rootPageId;
        public final String primaryKeyColumn;

        public TableInfo(String tableName, List<ColumnDef> columns, int rootPageId) {
            this.tableName = tableName;
            this.columns   = columns;
            this.rootPageId = rootPageId;
            this.primaryKeyColumn = columns.stream()
                .filter(ColumnDef::primaryKey)
                .map(ColumnDef::name)
                .findFirst()
                .orElse(null);
        }

        public boolean hasColumn(String name) {
            return columns.stream().anyMatch(c -> c.name().equals(name));
        }

        public ColumnDef getColumn(String name) {
            return columns.stream()
                .filter(c -> c.name().equals(name))
                .findFirst()
                .orElse(null);
        }
    }

    private final Map<String, TableInfo> tables = new ConcurrentHashMap<>();

    public void registerTable(String name, List<ColumnDef> columns, int rootPageId) {
        tables.put(name.toLowerCase(), new TableInfo(name, columns, rootPageId));
    }

    public TableInfo getTable(String name) {
        return tables.get(name.toLowerCase());
    }

    public boolean tableExists(String name) {
        return tables.containsKey(name.toLowerCase());
    }

    public void dropTable(String name) {
        tables.remove(name.toLowerCase());
    }

    public Map<String, TableInfo> allTables() {
        return Map.copyOf(tables);
    }
}
