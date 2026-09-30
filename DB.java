package com.library;

import java.sql.*;
import java.util.*;

/** Tiny JDBC helper. Override settings with env vars DB_URL, DB_USER, DB_PASS. */
public class DB {
    static final String URL  = env("DB_URL",  "jdbc:mysql://localhost:3306/library_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
    static final String USER = env("DB_USER", "root");
    static final String PASS = env("DB_PASS", "root");

    static { try { Class.forName("com.mysql.cj.jdbc.Driver"); } catch (Exception e) { throw new RuntimeException(e); } }

    static String env(String k, String d) { String v = System.getenv(k); return v == null ? d : v; }

    public static Connection get() throws SQLException { return DriverManager.getConnection(URL, USER, PASS); }

    public static List<Map<String, Object>> query(String sql, Object... args) throws SQLException {
        try (Connection c = get(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        Object v = rs.getObject(i);
                        if (v instanceof java.util.Date || v instanceof java.time.temporal.Temporal) v = v.toString();
                        row.put(md.getColumnLabel(i), v);
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    public static int update(String sql, Object... args) throws SQLException {
        try (Connection c = get()) { return update(c, sql, args); }
    }

    /** Runs on an existing connection (used for transactions). */
    public static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) { bind(ps, args); return ps.executeUpdate(); }
    }

    static void bind(PreparedStatement ps, Object[] a) throws SQLException {
        for (int i = 0; i < a.length; i++) ps.setObject(i + 1, a[i]);
    }
}
