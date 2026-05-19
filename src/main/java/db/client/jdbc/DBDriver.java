package db.client.jdbc;

import db.client.ClusterClient;
import db.server.DBServer;

import java.sql.*;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * JDBC Driver for the distributed-db engine.
 *
 * URL format: jdbc:distributeddb://
 *
 * For in-process testing, call DBDriver.connect(nodes) directly
 * to bypass DriverManager and obtain a Connection backed by a
 * ClusterClient holding live DBServer references.
 */
public class DBDriver implements Driver {

    private static final String URL_PREFIX = "jdbc:distributeddb://";
    private static final int MAJOR = 1;
    private static final int MINOR = 0;

    static {
        try {
            DriverManager.registerDriver(new DBDriver());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to register DBDriver", e);
        }
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return url != null && url.startsWith(URL_PREFIX);
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) return null;
        // In-process testing: accept a pre-built ClusterClient via properties
        if (info != null) {
            Object clientObj = info.get("cluster.client");
            if (clientObj instanceof ClusterClient client) {
                return new DBConnection(client);
            }
        }
        throw new SQLFeatureNotSupportedException(
            "TCP connections not implemented — pass cluster.client in Properties for in-process testing"
        );
    }

    /**
     * In-process connection: wraps a list of live DBServer objects.
     * Used by Phase5IntegrationTest to bypass TCP networking.
     */
    public static Connection connect(List<DBServer> nodes) {
        return new DBConnection(new ClusterClient(nodes));
    }

    @Override public int getMajorVersion() { return MAJOR; }
    @Override public int getMinorVersion() { return MINOR; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException("getParentLogger"); }
}
