package db.client.jdbc;

import db.sql.executor.Row;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal ResultSet implementation backed by a List<Row>.
 *
 * Implements the cursor model: starts before the first row.
 * next() advances the cursor and returns true until exhausted.
 * getString/getInt/getObject retrieve values from the current row.
 *
 * Only the methods needed for basic SELECT result iteration are
 * implemented. Others throw SQLFeatureNotSupportedException.
 */
public class DBResultSet implements ResultSet {

    private final List<Row>    rows;
    private final List<String> columnNames;
    private int cursor;   // -1 = before first row

    public DBResultSet(List<Row> rows) {
        this.rows        = rows;
        this.columnNames = rows.isEmpty()
            ? List.of()
            : new ArrayList<>(rows.get(0).columns());
        this.cursor = -1;
    }

    /** Empty result set — for INSERT/CREATE TABLE responses. */
    public static DBResultSet empty() {
        return new DBResultSet(List.of());
    }

    // ---------------------------------------------------------------
    //  Cursor movement
    // ---------------------------------------------------------------

    @Override public boolean next()       throws SQLException { cursor++; return cursor < rows.size(); }
    @Override public void    close()      throws SQLException { cursor = rows.size(); }
    @Override public boolean isClosed()   throws SQLException { return cursor >= rows.size(); }
    @Override public boolean isBeforeFirst() throws SQLException { return cursor == -1; }
    @Override public boolean isAfterLast()   throws SQLException { return cursor >= rows.size(); }
    @Override public boolean isFirst()       throws SQLException { return cursor == 0; }
    @Override public boolean isLast()        throws SQLException { return cursor == rows.size() - 1; }
    @Override public void    beforeFirst()   throws SQLException { cursor = -1; }
    @Override public int     getRow()        throws SQLException { return cursor + 1; }
    @Override public int     getType()       throws SQLException { return ResultSet.TYPE_FORWARD_ONLY; }
    @Override public int     getConcurrency()throws SQLException { return ResultSet.CONCUR_READ_ONLY; }
    @Override public int     getFetchDirection() throws SQLException { return ResultSet.FETCH_FORWARD; }
    @Override public void    setFetchDirection(int d) throws SQLException {}
    @Override public int     getFetchSize()  throws SQLException { return rows.size(); }
    @Override public void    setFetchSize(int r) throws SQLException {}
    @Override public int     getHoldability()throws SQLException { return ResultSet.CLOSE_CURSORS_AT_COMMIT; }

    // ---------------------------------------------------------------
    //  Value accessors
    // ---------------------------------------------------------------

    @Override
    public String getString(String col) throws SQLException {
        Object val = currentRow().get(col);
        return val == null ? null : val.toString();
    }

    @Override
    public int getInt(String col) throws SQLException {
        Object val = currentRow().get(col);
        if (val == null) return 0;
        if (val instanceof Integer i) return i;
        return Integer.parseInt(val.toString());
    }

    @Override
    public boolean getBoolean(String col) throws SQLException {
        Object val = currentRow().get(col);
        if (val == null) return false;
        if (val instanceof Boolean b) return b;
        return Boolean.parseBoolean(val.toString());
    }

    @Override public Object getObject(String col)   throws SQLException { return currentRow().get(col); }
    @Override public String getString(int i)         throws SQLException { return getString(col(i)); }
    @Override public int    getInt(int i)            throws SQLException { return getInt(col(i)); }
    @Override public Object getObject(int i)         throws SQLException { return getObject(col(i)); }
    @Override public boolean wasNull()               throws SQLException { return false; }
    @Override public boolean getBoolean(int i)       throws SQLException { return getBoolean(col(i)); }
    @Override public int     findColumn(String col)  throws SQLException { return columnNames.indexOf(col) + 1; }
    @Override public boolean rowInserted()           throws SQLException { return false; }
    @Override public boolean rowUpdated()            throws SQLException { return false; }
    @Override public boolean rowDeleted()            throws SQLException { return false; }

    // ---------------------------------------------------------------
    //  Metadata
    // ---------------------------------------------------------------

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        return new DBResultSetMetaData(columnNames);
    }

    // ---------------------------------------------------------------
    //  Internal helpers
    // ---------------------------------------------------------------

    private Row currentRow() throws SQLException {
        if (cursor < 0 || cursor >= rows.size())
            throw new SQLException("No current row — call next() first");
        return rows.get(cursor);
    }

    private String col(int columnIndex) { return columnNames.get(columnIndex - 1); }

    private SQLFeatureNotSupportedException unsupported(String m) {
        return new SQLFeatureNotSupportedException(m + " not supported");
    }

    // ---------------------------------------------------------------
    //  Unimplemented navigation
    // ---------------------------------------------------------------

    @Override public boolean absolute(int r)  throws SQLException { throw unsupported("absolute"); }
    @Override public boolean relative(int r)  throws SQLException { throw unsupported("relative"); }
    @Override public boolean previous()       throws SQLException { throw unsupported("previous"); }
    @Override public boolean last()           throws SQLException { throw unsupported("last"); }
    @Override public boolean first()          throws SQLException { throw unsupported("first"); }
    @Override public void    afterLast()      throws SQLException { throw unsupported("afterLast"); }

    // ---------------------------------------------------------------
    //  Unimplemented typed getters (by index)
    // ---------------------------------------------------------------

    @Override public byte   getByte(int i)    throws SQLException { throw unsupported("getByte"); }
    @Override public short  getShort(int i)   throws SQLException { throw unsupported("getShort"); }
    @Override public long   getLong(int i)    throws SQLException { throw unsupported("getLong"); }
    @Override public float  getFloat(int i)   throws SQLException { throw unsupported("getFloat"); }
    @Override public double getDouble(int i)  throws SQLException { throw unsupported("getDouble"); }
    @Override public byte[] getBytes(int i)   throws SQLException { throw unsupported("getBytes"); }
    @Override public java.sql.Date      getDate(int i)      throws SQLException { throw unsupported("getDate"); }
    @Override public java.sql.Time      getTime(int i)      throws SQLException { throw unsupported("getTime"); }
    @Override public java.sql.Timestamp getTimestamp(int i) throws SQLException { throw unsupported("getTimestamp"); }
    @Override public java.io.InputStream  getAsciiStream(int i)  throws SQLException { throw unsupported("getAsciiStream"); }
    @Override public java.io.InputStream  getUnicodeStream(int i)throws SQLException { throw unsupported("getUnicodeStream"); }
    @Override public java.io.InputStream  getBinaryStream(int i) throws SQLException { throw unsupported("getBinaryStream"); }
    @Override public java.io.Reader       getCharacterStream(int i) throws SQLException { throw unsupported("getCharacterStream"); }
    @Override public java.math.BigDecimal getBigDecimal(int i)   throws SQLException { throw unsupported("getBigDecimal"); }
    @Override public java.math.BigDecimal getBigDecimal(int i, int s) throws SQLException { throw unsupported("getBigDecimal"); }

    // ---------------------------------------------------------------
    //  Unimplemented typed getters (by name)
    // ---------------------------------------------------------------

    @Override public byte   getByte(String s)    throws SQLException { throw unsupported("getByte"); }
    @Override public short  getShort(String s)   throws SQLException { throw unsupported("getShort"); }
    @Override public long   getLong(String s)    throws SQLException { throw unsupported("getLong"); }
    @Override public float  getFloat(String s)   throws SQLException { throw unsupported("getFloat"); }
    @Override public double getDouble(String s)  throws SQLException { throw unsupported("getDouble"); }
    @Override public byte[] getBytes(String s)   throws SQLException { throw unsupported("getBytes"); }
    @Override public java.sql.Date      getDate(String s)      throws SQLException { throw unsupported("getDate"); }
    @Override public java.sql.Time      getTime(String s)      throws SQLException { throw unsupported("getTime"); }
    @Override public java.sql.Timestamp getTimestamp(String s) throws SQLException { throw unsupported("getTimestamp"); }
    @Override public java.io.InputStream  getAsciiStream(String s)   throws SQLException { throw unsupported("getAsciiStream"); }
    @Override public java.io.InputStream  getUnicodeStream(String s) throws SQLException { throw unsupported("getUnicodeStream"); }
    @Override public java.io.InputStream  getBinaryStream(String s)  throws SQLException { throw unsupported("getBinaryStream"); }
    @Override public java.io.Reader       getCharacterStream(String s) throws SQLException { throw unsupported("getCharacterStream"); }
    @Override public java.math.BigDecimal getBigDecimal(String s)    throws SQLException { throw unsupported("getBigDecimal"); }
    @Override public java.math.BigDecimal getBigDecimal(String s, int i) throws SQLException { throw unsupported("getBigDecimal"); }
    @Override public java.net.URL         getURL(int i)    throws SQLException { throw unsupported("getURL"); }
    @Override public java.net.URL         getURL(String s) throws SQLException { throw unsupported("getURL"); }

    // ---------------------------------------------------------------
    //  Unimplemented miscellaneous
    // ---------------------------------------------------------------

    @Override public String         getCursorName()         throws SQLException { throw unsupported("getCursorName"); }
    @Override public java.sql.SQLWarning getWarnings()      throws SQLException { return null; }
    @Override public void           clearWarnings()         throws SQLException {}
    @Override public java.sql.Statement getStatement()      throws SQLException { return null; }
    @Override public java.sql.Ref   getRef(int i)           throws SQLException { throw unsupported("getRef"); }
    @Override public java.sql.Blob  getBlob(int i)          throws SQLException { throw unsupported("getBlob"); }
    @Override public java.sql.Clob  getClob(int i)          throws SQLException { throw unsupported("getClob"); }
    @Override public java.sql.Array getArray(int i)         throws SQLException { throw unsupported("getArray"); }
    @Override public java.sql.Ref   getRef(String s)        throws SQLException { throw unsupported("getRef"); }
    @Override public java.sql.Blob  getBlob(String s)       throws SQLException { throw unsupported("getBlob"); }
    @Override public java.sql.Clob  getClob(String s)       throws SQLException { throw unsupported("getClob"); }
    @Override public java.sql.Array getArray(String s)      throws SQLException { throw unsupported("getArray"); }
    @Override public java.sql.Date  getDate(int i, java.util.Calendar c)      throws SQLException { throw unsupported("getDate"); }
    @Override public java.sql.Date  getDate(String s, java.util.Calendar c)   throws SQLException { throw unsupported("getDate"); }
    @Override public java.sql.Time  getTime(int i, java.util.Calendar c)      throws SQLException { throw unsupported("getTime"); }
    @Override public java.sql.Time  getTime(String s, java.util.Calendar c)   throws SQLException { throw unsupported("getTime"); }
    @Override public java.sql.Timestamp getTimestamp(int i, java.util.Calendar c)    throws SQLException { throw unsupported("getTimestamp"); }
    @Override public java.sql.Timestamp getTimestamp(String s, java.util.Calendar c) throws SQLException { throw unsupported("getTimestamp"); }
    @Override public java.sql.RowId getRowId(int i)   throws SQLException { throw unsupported("getRowId"); }
    @Override public java.sql.RowId getRowId(String s) throws SQLException { throw unsupported("getRowId"); }
    @Override public java.sql.NClob getNClob(int i)   throws SQLException { throw unsupported("getNClob"); }
    @Override public java.sql.NClob getNClob(String s) throws SQLException { throw unsupported("getNClob"); }
    @Override public java.sql.SQLXML getSQLXML(int i)   throws SQLException { throw unsupported("getSQLXML"); }
    @Override public java.sql.SQLXML getSQLXML(String s) throws SQLException { throw unsupported("getSQLXML"); }
    @Override public String getNString(int i)   throws SQLException { throw unsupported("getNString"); }
    @Override public String getNString(String s) throws SQLException { throw unsupported("getNString"); }
    @Override public java.io.Reader getNCharacterStream(int i)   throws SQLException { throw unsupported("getNCharacterStream"); }
    @Override public java.io.Reader getNCharacterStream(String s) throws SQLException { throw unsupported("getNCharacterStream"); }
    @Override public Object getObject(int i, java.util.Map<String,Class<?>> m)    throws SQLException { throw unsupported("getObject"); }
    @Override public Object getObject(String s, java.util.Map<String,Class<?>> m) throws SQLException { throw unsupported("getObject"); }
    @Override public <T> T  getObject(int i, Class<T> t)    throws SQLException { throw unsupported("getObject"); }
    @Override public <T> T  getObject(String s, Class<T> t) throws SQLException { throw unsupported("getObject"); }

    // ---------------------------------------------------------------
    //  All update methods — read-only ResultSet, always unsupported
    // ---------------------------------------------------------------

    @Override public void updateNull(int i)           throws SQLException { throw unsupported("updateNull"); }
    @Override public void updateBoolean(int i, boolean v)        throws SQLException { throw unsupported("updateBoolean"); }
    @Override public void updateByte(int i, byte v)              throws SQLException { throw unsupported("updateByte"); }
    @Override public void updateShort(int i, short v)            throws SQLException { throw unsupported("updateShort"); }
    @Override public void updateInt(int i, int v)                throws SQLException { throw unsupported("updateInt"); }
    @Override public void updateLong(int i, long v)              throws SQLException { throw unsupported("updateLong"); }
    @Override public void updateFloat(int i, float v)            throws SQLException { throw unsupported("updateFloat"); }
    @Override public void updateDouble(int i, double v)          throws SQLException { throw unsupported("updateDouble"); }
    @Override public void updateBigDecimal(int i, java.math.BigDecimal v) throws SQLException { throw unsupported("updateBigDecimal"); }
    @Override public void updateString(int i, String v)          throws SQLException { throw unsupported("updateString"); }
    @Override public void updateBytes(int i, byte[] v)           throws SQLException { throw unsupported("updateBytes"); }
    @Override public void updateDate(int i, java.sql.Date v)     throws SQLException { throw unsupported("updateDate"); }
    @Override public void updateTime(int i, java.sql.Time v)     throws SQLException { throw unsupported("updateTime"); }
    @Override public void updateTimestamp(int i, java.sql.Timestamp v) throws SQLException { throw unsupported("updateTimestamp"); }
    @Override public void updateAsciiStream(int i, java.io.InputStream v, int l)     throws SQLException { throw unsupported("updateAsciiStream"); }
    @Override public void updateBinaryStream(int i, java.io.InputStream v, int l)    throws SQLException { throw unsupported("updateBinaryStream"); }
    @Override public void updateCharacterStream(int i, java.io.Reader v, int l)      throws SQLException { throw unsupported("updateCharacterStream"); }
    @Override public void updateObject(int i, Object v, int s)   throws SQLException { throw unsupported("updateObject"); }
    @Override public void updateObject(int i, Object v)          throws SQLException { throw unsupported("updateObject"); }
    @Override public void updateNull(String s)                   throws SQLException { throw unsupported("updateNull"); }
    @Override public void updateBoolean(String s, boolean v)     throws SQLException { throw unsupported("updateBoolean"); }
    @Override public void updateByte(String s, byte v)           throws SQLException { throw unsupported("updateByte"); }
    @Override public void updateShort(String s, short v)         throws SQLException { throw unsupported("updateShort"); }
    @Override public void updateInt(String s, int v)             throws SQLException { throw unsupported("updateInt"); }
    @Override public void updateLong(String s, long v)           throws SQLException { throw unsupported("updateLong"); }
    @Override public void updateFloat(String s, float v)         throws SQLException { throw unsupported("updateFloat"); }
    @Override public void updateDouble(String s, double v)       throws SQLException { throw unsupported("updateDouble"); }
    @Override public void updateBigDecimal(String s, java.math.BigDecimal v) throws SQLException { throw unsupported("updateBigDecimal"); }
    @Override public void updateString(String s, String v)       throws SQLException { throw unsupported("updateString"); }
    @Override public void updateBytes(String s, byte[] v)        throws SQLException { throw unsupported("updateBytes"); }
    @Override public void updateDate(String s, java.sql.Date v)  throws SQLException { throw unsupported("updateDate"); }
    @Override public void updateTime(String s, java.sql.Time v)  throws SQLException { throw unsupported("updateTime"); }
    @Override public void updateTimestamp(String s, java.sql.Timestamp v) throws SQLException { throw unsupported("updateTimestamp"); }
    @Override public void updateAsciiStream(String s, java.io.InputStream v, int l)  throws SQLException { throw unsupported("updateAsciiStream"); }
    @Override public void updateBinaryStream(String s, java.io.InputStream v, int l) throws SQLException { throw unsupported("updateBinaryStream"); }
    @Override public void updateCharacterStream(String s, java.io.Reader v, int l)   throws SQLException { throw unsupported("updateCharacterStream"); }
    @Override public void updateObject(String s, Object v, int l) throws SQLException { throw unsupported("updateObject"); }
    @Override public void updateObject(String s, Object v)        throws SQLException { throw unsupported("updateObject"); }
    @Override public void updateAsciiStream(int i, java.io.InputStream n, long l)    throws SQLException { throw unsupported("updateAsciiStream"); }
    @Override public void updateBinaryStream(int i, java.io.InputStream n, long l)   throws SQLException { throw unsupported("updateBinaryStream"); }
    @Override public void updateCharacterStream(int i, java.io.Reader r, long l)     throws SQLException { throw unsupported("updateCharacterStream"); }
    @Override public void updateAsciiStream(String s, java.io.InputStream n, long l) throws SQLException { throw unsupported("updateAsciiStream"); }
    @Override public void updateBinaryStream(String s, java.io.InputStream n, long l)throws SQLException { throw unsupported("updateBinaryStream"); }
    @Override public void updateCharacterStream(String s, java.io.Reader r, long l)  throws SQLException { throw unsupported("updateCharacterStream"); }
    @Override public void updateBlob(int i, java.io.InputStream n, long l)           throws SQLException { throw unsupported("updateBlob"); }
    @Override public void updateBlob(String s, java.io.InputStream n, long l)        throws SQLException { throw unsupported("updateBlob"); }
    @Override public void updateClob(int i, java.io.Reader r, long l)                throws SQLException { throw unsupported("updateClob"); }
    @Override public void updateClob(String s, java.io.Reader r, long l)             throws SQLException { throw unsupported("updateClob"); }
    @Override public void updateNClob(int i, java.io.Reader r, long l)               throws SQLException { throw unsupported("updateNClob"); }
    @Override public void updateNClob(String s, java.io.Reader r, long l)            throws SQLException { throw unsupported("updateNClob"); }
    @Override public void updateNCharacterStream(int i, java.io.Reader r, long l)    throws SQLException { throw unsupported("updateNCharacterStream"); }
    @Override public void updateNCharacterStream(String s, java.io.Reader r, long l) throws SQLException { throw unsupported("updateNCharacterStream"); }
    @Override public void updateNCharacterStream(int i, java.io.Reader r)            throws SQLException { throw unsupported("updateNCharacterStream"); }
    @Override public void updateNCharacterStream(String s, java.io.Reader r)         throws SQLException { throw unsupported("updateNCharacterStream"); }
    @Override public void updateAsciiStream(int i, java.io.InputStream n)            throws SQLException { throw unsupported("updateAsciiStream"); }
    @Override public void updateBinaryStream(int i, java.io.InputStream n)           throws SQLException { throw unsupported("updateBinaryStream"); }
    @Override public void updateCharacterStream(int i, java.io.Reader r)             throws SQLException { throw unsupported("updateCharacterStream"); }
    @Override public void updateAsciiStream(String s, java.io.InputStream n)         throws SQLException { throw unsupported("updateAsciiStream"); }
    @Override public void updateBinaryStream(String s, java.io.InputStream n)        throws SQLException { throw unsupported("updateBinaryStream"); }
    @Override public void updateCharacterStream(String s, java.io.Reader r)          throws SQLException { throw unsupported("updateCharacterStream"); }
    @Override public void updateBlob(int i, java.io.InputStream n)                   throws SQLException { throw unsupported("updateBlob"); }
    @Override public void updateBlob(String s, java.io.InputStream n)                throws SQLException { throw unsupported("updateBlob"); }
    @Override public void updateClob(int i, java.io.Reader r)                        throws SQLException { throw unsupported("updateClob"); }
    @Override public void updateClob(String s, java.io.Reader r)                     throws SQLException { throw unsupported("updateClob"); }
    @Override public void updateNClob(int i, java.io.Reader r)                       throws SQLException { throw unsupported("updateNClob"); }
    @Override public void updateNClob(String s, java.io.Reader r)                    throws SQLException { throw unsupported("updateNClob"); }
    @Override public void updateNClob(int i, java.sql.NClob c)                       throws SQLException { throw unsupported("updateNClob"); }
    @Override public void updateNClob(String s, java.sql.NClob c)                    throws SQLException { throw unsupported("updateNClob"); }
    @Override public void updateNString(int i, String s)                             throws SQLException { throw unsupported("updateNString"); }
    @Override public void updateNString(String s, String v)                          throws SQLException { throw unsupported("updateNString"); }
    @Override public void updateRef(int i, java.sql.Ref x)                 throws SQLException { throw unsupported("updateRef"); }
    @Override public void updateRef(String s, java.sql.Ref x)              throws SQLException { throw unsupported("updateRef"); }
    @Override public void updateBlob(int i, java.sql.Blob x)               throws SQLException { throw unsupported("updateBlob"); }
    @Override public void updateBlob(String s, java.sql.Blob x)            throws SQLException { throw unsupported("updateBlob"); }
    @Override public void updateClob(int i, java.sql.Clob x)               throws SQLException { throw unsupported("updateClob"); }
    @Override public void updateClob(String s, java.sql.Clob x)            throws SQLException { throw unsupported("updateClob"); }
    @Override public void updateArray(int i, java.sql.Array x)             throws SQLException { throw unsupported("updateArray"); }
    @Override public void updateArray(String s, java.sql.Array x)          throws SQLException { throw unsupported("updateArray"); }
    @Override public void updateRowId(int i, java.sql.RowId r)                       throws SQLException { throw unsupported("updateRowId"); }
    @Override public void updateRowId(String s, java.sql.RowId r)                    throws SQLException { throw unsupported("updateRowId"); }
    @Override public void updateSQLXML(int i, java.sql.SQLXML x)                     throws SQLException { throw unsupported("updateSQLXML"); }
    @Override public void updateSQLXML(String s, java.sql.SQLXML x)                  throws SQLException { throw unsupported("updateSQLXML"); }
    @Override public void insertRow()      throws SQLException { throw unsupported("insertRow"); }
    @Override public void updateRow()      throws SQLException { throw unsupported("updateRow"); }
    @Override public void deleteRow()      throws SQLException { throw unsupported("deleteRow"); }
    @Override public void refreshRow()     throws SQLException { throw unsupported("refreshRow"); }
    @Override public void cancelRowUpdates() throws SQLException { throw unsupported("cancelRowUpdates"); }
    @Override public void moveToInsertRow()  throws SQLException { throw unsupported("moveToInsertRow"); }
    @Override public void moveToCurrentRow() throws SQLException { throw unsupported("moveToCurrentRow"); }

    @Override public <T> T  unwrap(Class<T> t)           throws SQLException { throw unsupported("unwrap"); }
    @Override public boolean isWrapperFor(Class<?> t)     throws SQLException { return false; }
}
