package com.example.inventory.auth;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DelegatingDataSource;

final class RecordingDataSource extends DelegatingDataSource {

    private final List<String> statements = new CopyOnWriteArrayList<>();

    RecordingDataSource(DataSource target) {
        super(target);
    }

    List<String> statements() {
        return List.copyOf(statements);
    }

    int indexOf(String fragment) {
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i).contains(fragment)) {
                return i;
            }
        }
        return -1;
    }

    boolean sawAny(String fragment) {
        return indexOf(fragment) >= 0;
    }

    void reset() {
        statements.clear();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return record(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return record(super.getConnection(username, password));
    }

    private Connection record(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                RecordingDataSource.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if (("prepareStatement".equals(method.getName())
                            || "createStatement".equals(method.getName()))
                            && args != null && args.length > 0 && args[0] instanceof String sql) {
                        statements.add(sql);
                    }
                    return invoke(connection, method, args);
                });
    }

    private static Object invoke(Connection target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }
}
