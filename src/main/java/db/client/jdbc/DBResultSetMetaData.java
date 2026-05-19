package db.client.jdbc;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;

/**
 * Minimal ResultSetMetaData — column names and count only.
 */
public class DBResultSetMetaData implements ResultSetMetaData {

    private final List<String> columnNames;

    public DBResultSetMetaData(List<String> columnNames) {
        this.columnNames = columnNames;
    }

    @Override public int    getColumnCount()                   throws SQLException { return columnNames.size(); }
    @Override public String getColumnName(int column)          throws SQLException { return columnNames.get(column - 1); }
    @Override public String getColumnLabel(int column)         throws SQLException { return getColumnName(column); }
    @Override public int    getColumnType(int column)          throws SQLException { return java.sql.Types.OTHER; }
    @Override public String getColumnTypeName(int column)      throws SQLException { return "UNKNOWN"; }
    @Override public String getColumnClassName(int column)     throws SQLException { return "java.lang.Object"; }
    @Override public String getSchemaName(int column)          throws SQLException { return ""; }
    @Override public String getTableName(int column)           throws SQLException { return ""; }
    @Override public String getCatalogName(int column)         throws SQLException { return ""; }
    @Override public int    getPrecision(int column)           throws SQLException { return 0; }
    @Override public int    getScale(int column)               throws SQLException { return 0; }
    @Override public int    getColumnDisplaySize(int column)   throws SQLException { return 255; }
    @Override public boolean isAutoIncrement(int column)       throws SQLException { return false; }
    @Override public boolean isCaseSensitive(int column)       throws SQLException { return true; }
    @Override public boolean isSearchable(int column)          throws SQLException { return true; }
    @Override public boolean isCurrency(int column)            throws SQLException { return false; }
    @Override public int    isNullable(int column)             throws SQLException { return ResultSetMetaData.columnNullable; }
    @Override public boolean isSigned(int column)              throws SQLException { return false; }
    @Override public boolean isReadOnly(int column)            throws SQLException { return true; }
    @Override public boolean isWritable(int column)            throws SQLException { return false; }
    @Override public boolean isDefinitelyWritable(int column)  throws SQLException { return false; }
    @Override public <T> T  unwrap(Class<T> iface)             throws SQLException { throw new SQLFeatureNotSupportedException("unwrap"); }
    @Override public boolean isWrapperFor(Class<?> iface)      throws SQLException { return false; }
}
