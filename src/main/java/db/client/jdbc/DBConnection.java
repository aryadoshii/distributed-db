package db.client.jdbc;

import db.client.ClusterClient;

import java.sql.*;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * Minimal Connection backed by ClusterClient.
 * createStatement() is the only meaningful method — everything else
 * is a no-op or throws SQLFeatureNotSupportedException.
 */
public class DBConnection implements Connection {

    private final ClusterClient client;
    private boolean closed;
    private boolean autoCommit = true;

    public DBConnection(ClusterClient client) {
        this.client = client;
        this.closed = false;
    }

    @Override
    public Statement createStatement() throws SQLException {
        checkOpen();
        return new DBStatement(client);
    }

    @Override
    public void close() throws SQLException { closed = true; }

    @Override public boolean isClosed() throws SQLException { return closed; }

    @Override public boolean getAutoCommit() throws SQLException { return autoCommit; }
    @Override public void    setAutoCommit(boolean a) throws SQLException { autoCommit = a; }
    @Override public void    commit()   throws SQLException {}
    @Override public void    rollback() throws SQLException {}

    @Override public DatabaseMetaData getMetaData() throws SQLException { throw unsupported("getMetaData"); }

    private void checkOpen() throws SQLException {
        if (closed) throw new SQLException("Connection is closed");
    }

    private SQLFeatureNotSupportedException unsupported(String m) {
        return new SQLFeatureNotSupportedException(m + " not supported");
    }

    // ---------------------------------------------------------------
    //  Minimal stubs — not used for in-process testing
    // ---------------------------------------------------------------

    @Override public PreparedStatement prepareStatement(String sql) throws SQLException { throw unsupported("prepareStatement"); }
    @Override public CallableStatement prepareCall(String sql)       throws SQLException { throw unsupported("prepareCall"); }
    @Override public String nativeSQL(String sql)                    throws SQLException { return sql; }
    @Override public void   setReadOnly(boolean r)                   throws SQLException {}
    @Override public boolean isReadOnly()                            throws SQLException { return false; }
    @Override public void   setCatalog(String c)                     throws SQLException {}
    @Override public String getCatalog()                             throws SQLException { return ""; }
    @Override public void   setTransactionIsolation(int l)           throws SQLException {}
    @Override public int    getTransactionIsolation()                throws SQLException { return TRANSACTION_READ_COMMITTED; }
    @Override public SQLWarning getWarnings()                        throws SQLException { return null; }
    @Override public void   clearWarnings()                          throws SQLException {}
    @Override public Statement createStatement(int rt, int rc)       throws SQLException { return createStatement(); }
    @Override public PreparedStatement prepareStatement(String sql, int rt, int rc) throws SQLException { throw unsupported("prepareStatement"); }
    @Override public CallableStatement prepareCall(String sql, int rt, int rc)      throws SQLException { throw unsupported("prepareCall"); }
    @Override public java.util.Map<String,Class<?>> getTypeMap()     throws SQLException { return java.util.Collections.emptyMap(); }
    @Override public void   setTypeMap(java.util.Map<String,Class<?>> m) throws SQLException {}
    @Override public void   setHoldability(int h)                    throws SQLException {}
    @Override public int    getHoldability()                         throws SQLException { return ResultSet.CLOSE_CURSORS_AT_COMMIT; }
    @Override public Savepoint setSavepoint()                        throws SQLException { throw unsupported("setSavepoint"); }
    @Override public Savepoint setSavepoint(String name)             throws SQLException { throw unsupported("setSavepoint"); }
    @Override public void   rollback(Savepoint s)                    throws SQLException {}
    @Override public void   releaseSavepoint(Savepoint s)            throws SQLException {}
    @Override public Statement createStatement(int rt, int rc, int rh) throws SQLException { return createStatement(); }
    @Override public PreparedStatement prepareStatement(String sql, int rt, int rc, int rh) throws SQLException { throw unsupported("prepareStatement"); }
    @Override public CallableStatement prepareCall(String sql, int rt, int rc, int rh)      throws SQLException { throw unsupported("prepareCall"); }
    @Override public PreparedStatement prepareStatement(String sql, int ag) throws SQLException { throw unsupported("prepareStatement"); }
    @Override public PreparedStatement prepareStatement(String sql, int[] ci)throws SQLException { throw unsupported("prepareStatement"); }
    @Override public PreparedStatement prepareStatement(String sql, String[] cn) throws SQLException { throw unsupported("prepareStatement"); }
    @Override public Clob   createClob()   throws SQLException { throw unsupported("createClob"); }
    @Override public Blob   createBlob()   throws SQLException { throw unsupported("createBlob"); }
    @Override public NClob  createNClob()  throws SQLException { throw unsupported("createNClob"); }
    @Override public SQLXML createSQLXML() throws SQLException { throw unsupported("createSQLXML"); }
    @Override public boolean isValid(int t)throws SQLException { return !closed; }
    @Override public void   setClientInfo(String k, String v) throws SQLClientInfoException {}
    @Override public void   setClientInfo(Properties p)       throws SQLClientInfoException {}
    @Override public String getClientInfo(String k)           throws SQLException { return null; }
    @Override public Properties getClientInfo()               throws SQLException { return new Properties(); }
    @Override public Array  createArrayOf(String t, Object[] e) throws SQLException { throw unsupported("createArrayOf"); }
    @Override public Struct createStruct(String t, Object[] a)  throws SQLException { throw unsupported("createStruct"); }
    @Override public void   setSchema(String s) throws SQLException {}
    @Override public String getSchema()         throws SQLException { return ""; }
    @Override public void   abort(Executor e)   throws SQLException {}
    @Override public void   setNetworkTimeout(Executor e, int ms) throws SQLException {}
    @Override public int    getNetworkTimeout() throws SQLException { return 0; }
    @Override public <T> T  unwrap(Class<T> t)  throws SQLException { throw unsupported("unwrap"); }
    @Override public boolean isWrapperFor(Class<?> t) throws SQLException { return false; }
}
