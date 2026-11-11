package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The repositories' own statements for the SAH-34 plan and comparison tests. The constants of the repository classes are
 * package-private, so they are read by reflection: a changed statement is the statement measured, and no copy can drift.
 * The named parameters ({@code :tenantId}) become real JDBC parameters bound the way {@code JdbcClient} binds them (an array is
 * a text literal that the statement casts), which matters for the plans: a literal written into the text is folded by the
 * planner, a bound parameter is not.
 */
final class RealSql {

    static final String WARD_REPO = "com.sahaj.ipd.infrastructure.persistence.JdbcWardScopeRepository";
    static final String EVENT_REPO = "com.sahaj.ipd.infrastructure.persistence.JdbcClinicalEventRepository";
    static final String ROUND_REPO = "com.sahaj.ipd.infrastructure.persistence.JdbcMedicineRoundRepository";
    static final String ADMISSION_REPO = "com.sahaj.ipd.infrastructure.persistence.JdbcAdmissionRepository";

    private static final Pattern NAMED = Pattern.compile("(?<!:):([A-Za-z]+)\\b");

    /** A statement with its named parameters turned into JDBC parameters, values in order. */
    record Stmt(String sql, List<Object> values) {
    }

    private RealSql() {
    }

    static Stmt bind(String named, Map<String, Object> params) {
        List<Object> values = new ArrayList<>();
        Matcher m = NAMED.matcher(named);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            assertThat(params).as("parameter :" + m.group(1)).containsKey(m.group(1));
            values.add(params.get(m.group(1)));
            m.appendReplacement(sb, "?");
        }
        m.appendTail(sb);
        return new Stmt(sb.toString(), values);
    }

    static Map<String, Object> params(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    static String constant(String type, String name) throws Exception {
        Field f = Class.forName(type).getDeclaredField(name);
        f.setAccessible(true);
        return (String) f.get(null);
    }

    static String arrayLiteral(Collection<String> ids) throws Exception {
        Method m = Class.forName(ROUND_REPO).getDeclaredMethod("arrayLiteral", Collection.class);
        m.setAccessible(true);
        List<UUID> uuids = new ArrayList<>();
        ids.forEach(i -> uuids.add(UUID.fromString(i)));
        return (String) m.invoke(null, uuids);
    }

    /** The scoped-nurse page statement exactly as {@code findPageInScope} chooses it. */
    static String pageSql(boolean actorOnly, boolean keyset) throws Exception {
        Method m = Class.forName(EVENT_REPO).getDeclaredMethod("pageInScopeSql", boolean.class, boolean.class);
        m.setAccessible(true);
        return (String) m.invoke(null, actorOnly, keyset);
    }

    /** Runs a statement on the given connection (bound as sahaj_app for a clinic by the caller) and returns column 1 of every row. */
    static List<String> firstColumn(Connection c, Stmt stmt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(stmt.sql())) {
            for (int i = 0; i < stmt.values().size(); i++) {
                ps.setObject(i + 1, stmt.values().get(i));
            }
            List<String> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        }
    }
}
