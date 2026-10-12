package com.example.inventory.web;

import java.sql.SQLException;

public final class SqlStates {

    private SqlStates() {
    }

    public static final String INSUFFICIENT_PRIVILEGE = "42501";

    public static final String CHECK_VIOLATION = "23514";

    public static final String FOREIGN_KEY_VIOLATION = "23503";

    public static String of(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return null;
    }

    public static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
