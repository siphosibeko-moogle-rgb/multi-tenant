package com.example.inventory.tenancy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DelegatingDataSource;

public class TenantConnectionProvider extends DelegatingDataSource {

    private static final String SET_TENANT = "SELECT set_config('app.tenant_id', ?, false)";
    private static final String SET_USER = "SELECT set_config('app.user_id', ?, false)";

    public TenantConnectionProvider(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return bind(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return bind(super.getConnection(username, password));
    }

    private Connection bind(Connection connection) throws SQLException {
        try {
            applyCurrentTenant(connection);
        } catch (SQLException | RuntimeException e) {
            closeQuietly(connection);
            throw e;
        }
        return proxy(connection);
    }

    private static void applyCurrentTenant(Connection connection) throws SQLException {
        var identity = TenantContext.current().orElse(null);
        setConfig(connection, SET_TENANT, identity == null ? null : identity.tenantId());
        setConfig(connection, SET_USER, identity == null ? null : identity.userId());
    }

    private static void setConfig(Connection connection, String sql, UUID value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, value == null ? "" : value.toString());
            ps.execute();
        }
    }

    private static Connection proxy(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                TenantConnectionProvider.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                new ClearOnClose(connection));
    }

    private record ClearOnClose(Connection target) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("close".equals(method.getName()) && (args == null || args.length == 0)) {
                clearThenClose();
                return null;
            }
            if ("equals".equals(method.getName()) && args != null && args.length == 1) {
                return proxy == args[0];
            }
            if ("hashCode".equals(method.getName()) && (args == null || args.length == 0)) {
                return System.identityHashCode(proxy);
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        }

        private void clearThenClose() throws SQLException {
            try {
                if (!target.isClosed()) {
                    setConfig(target, SET_TENANT, null);
                    setConfig(target, SET_USER, null);
                }
            } catch (SQLException e) {
                closeQuietly(target);
                throw e;
            }
            target.close();
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}
