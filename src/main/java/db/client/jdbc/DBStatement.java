package db.client.jdbc;

import db.client.ClusterClient;
import db.sql.executor.Row;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Statement implementation backed by ClusterClient.
 *
 * execute(sql)       — routes to ClusterClient.execute() or .query()
 * executeQuery(sql)  — always calls query() (SELECT)
 * executeUpdate(sql) — always calls execute() (INSERT/CREATE/UPDATE/DELETE)
 *
 * The result is a DBResultSet parsed from the state machine's
 * newline-separated row output.
 */
public class DBStatement implements Statement {

    private final ClusterClient client;
    private ResultSet lastResultSet;
    private int       updateCount;
    private boolean   closed;

    public DBStatement(ClusterClient client) {
        this.client      = client;
        this.updateCount = -1;
        this.closed      = false;
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        checkOpen();
        String result = client.query(sql);
        lastResultSet = parseResultSet(result);
        return lastResultSet;
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        checkOpen();
        String result = client.execute(sql);
        updateCount   = parseUpdateCount(result);
        return updateCount;
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        checkOpen();
        String upper = sql.trim().toUpperCase();
        if (upper.startsWith("SELECT")) {
            lastResultSet = parseResultSet(client.query(sql));
            updateCount   = -1;
            return true;   // has ResultSet
        } else {
            updateCount   = parseUpdateCount(client.execute(sql));
            lastResultSet = null;
            return false;  // no ResultSet
        }
    }

    @Override public ResultSet getResultSet()   throws SQLException { return lastResultSet; }
    @Override public int       getUpdateCount() throws SQLException { return updateCount; }

    @Override
    public void close() throws SQLException {
        closed = true;
        if (lastResultSet != null) lastResultSet.close();
    }

    @Override public boolean isClosed() throws SQLException { return closed; }

    // ---------------------------------------------------------------
    //  Result parsing
    // ---------------------------------------------------------------

    /**
     * Parse the state machine's text output into a DBResultSet.
     * The state machine returns row.toString() lines: {col=val, ...}\n
     * Each line becomes a single-column "result" row for JDBC consumers.
     */
    private DBResultSet parseResultSet(String output) {
        List<Row> rows = new ArrayList<>();
        if (output != null && !output.isBlank()) {
            for (String line : output.split("\n")) {
                if (line.isBlank()) continue;
                Row row = new Row();
                row.put("result", line.trim());
                rows.add(row);
            }
        }
        return new DBResultSet(rows);
    }

    private int parseUpdateCount(String result) {
        if (result == null) return 0;
        if (result.contains("affected_rows=1")) return 1;
        return 0;
    }

    // ---------------------------------------------------------------
    //  Minimal no-op / unsupported implementations
    // ---------------------------------------------------------------

    private void checkOpen() throws SQLException {
        if (closed) throw new SQLException("Statement is closed");
    }

    private SQLFeatureNotSupportedException unsupported(String m) {
        return new SQLFeatureNotSupportedException(m + " not supported");
    }

    @Override public Connection  getConnection()    throws SQLException { return null; }
    @Override public int  getMaxFieldSize()         throws SQLException { return 0; }
    @Override public void setMaxFieldSize(int m)    throws SQLException {}
    @Override public int  getMaxRows()              throws SQLException { return 0; }
    @Override public void setMaxRows(int m)         throws SQLException {}
    @Override public void setEscapeProcessing(boolean e) throws SQLException {}
    @Override public int  getQueryTimeout()         throws SQLException { return 0; }
    @Override public void setQueryTimeout(int s)    throws SQLException {}
    @Override public void cancel()                  throws SQLException {}
    @Override public SQLWarning getWarnings()       throws SQLException { return null; }
    @Override public void clearWarnings()           throws SQLException {}
    @Override public void setCursorName(String n)   throws SQLException {}
    @Override public boolean getMoreResults()       throws SQLException { return false; }
    @Override public void setFetchDirection(int d)  throws SQLException {}
    @Override public int  getFetchDirection()       throws SQLException { return ResultSet.FETCH_FORWARD; }
    @Override public void setFetchSize(int r)       throws SQLException {}
    @Override public int  getFetchSize()            throws SQLException { return 0; }
    @Override public int  getResultSetConcurrency() throws SQLException { return ResultSet.CONCUR_READ_ONLY; }
    @Override public int  getResultSetType()        throws SQLException { return ResultSet.TYPE_FORWARD_ONLY; }
    @Override public void addBatch(String sql)      throws SQLException { throw unsupported("addBatch"); }
    @Override public void clearBatch()              throws SQLException { throw unsupported("clearBatch"); }
    @Override public int[] executeBatch()           throws SQLException { throw unsupported("executeBatch"); }
    @Override public boolean getMoreResults(int c)  throws SQLException { return false; }
    @Override public ResultSet getGeneratedKeys()   throws SQLException { return DBResultSet.empty(); }
    @Override public int  executeUpdate(String sql, int a)    throws SQLException { return executeUpdate(sql); }
    @Override public int  executeUpdate(String sql, int[] c)  throws SQLException { return executeUpdate(sql); }
    @Override public int  executeUpdate(String sql, String[] c) throws SQLException { return executeUpdate(sql); }
    @Override public boolean execute(String sql, int a)       throws SQLException { return execute(sql); }
    @Override public boolean execute(String sql, int[] c)     throws SQLException { return execute(sql); }
    @Override public boolean execute(String sql, String[] c)  throws SQLException { return execute(sql); }
    @Override public int  getResultSetHoldability() throws SQLException { return ResultSet.CLOSE_CURSORS_AT_COMMIT; }
    @Override public void setPoolable(boolean p)    throws SQLException {}
    @Override public boolean isPoolable()           throws SQLException { return false; }
    @Override public void closeOnCompletion()       throws SQLException {}
    @Override public boolean isCloseOnCompletion()  throws SQLException { return false; }
    @Override public <T> T  unwrap(Class<T> t)      throws SQLException { throw unsupported("unwrap"); }
    @Override public boolean isWrapperFor(Class<?> t) throws SQLException { return false; }
}
