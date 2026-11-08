/*
 * Copyright 2026 杭州开云集致科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.clougence.clouddm.ds.metadata.dameng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.clougence.adapter.dameng.DmAttributeNames;
import com.clougence.clouddm.ds.dameng.execute.DmMetaProviderDm;
import com.clougence.schema.umi.special.rdb.RdbColumn;
import com.clougence.schema.umi.struts.UmiAttributeNames;
import com.clougence.schema.umi.struts.Value;
import com.clougence.schema.umi.struts.constraint.ConstraintObject;

/**
 * Covers the dictionary privilege handling of {@link DmMetaProviderDm}.
 *
 * <p>DM exposes the Oracle style dictionary twice: the {@code DBA_*} views hold every object but are readable only by
 * accounts holding the DBA role, while {@code ALL_*} holds the objects the account can reach. A DQL-only account is
 * rejected at prepare time with "没有[SYS.DBA_TAB_COLS]对象的查询权限", which used to break the whole table detail
 * (columns and constraints) of the browse panel. These cases lock down the fallback:
 * <ul>
 * <li>the DBA flavour is kept whenever the account may read it, so privileged accounts see the same SQL as before;</li>
 * <li>a rejected {@code SYS.DBA_TAB_COLS} switches every dictionary read to the {@code ALL_*} views;</li>
 * <li>the privilege check runs once per provider, not once per dictionary read.</li>
 * </ul>
 */
public class DmMetaProviderDmTest {

    private static final String            SCHEMA = "BIZ";

    private static final UmiAttributeNames HIDDEN = DmAttributeNames.HIDDEN;

    @Test
    void fetchTableColumns_dqlOnlyAccount_readsAllTabColumns() throws Exception {
        CapturingConnection connection = new CapturingConnection(true);
        connection.routeRows("SYS.ALL_TAB_COLUMNS", Collections.singletonList(columnRow("CUS_REGION", "REGION_CD", "VARCHAR", 0, "N", 80L, 20L, null, "region code")));

        Map<String, List<RdbColumn>> columns = fetchTableColumns(connection, Collections.singletonList("CUS_REGION"));
        RdbColumn column = columns.get("CUS_REGION").get(0);

        assertEquals("CUS_REGION", column.getTable());
        assertEquals("REGION_CD", column.getName());
        assertEquals("region code", column.getComment());
        assertEquals(80L, column.getByteLength());
        assertEquals(20L, column.getCharLength());
        assertTrue(column.getConstraints() != null && !column.getConstraints().isEmpty(), "NULLABLE=N should add a non-null constraint");
        assertTrue("false".equals(column.getAttribute(HIDDEN)), "the ALL_ view never reports hidden columns");

        String columnSql = connection.sqlOf("order by COLUMN_ID");
        assertNotNull(columnSql, "columns should be read: " + connection.issuedSql());
        assertTrue(columnSql.contains("SYS.ALL_TAB_COLUMNS"), columnSql);
        assertTrue(columnSql.contains("SYS.ALL_COL_COMMENTS"), columnSql);
        assertFalse(columnSql.contains("SYS.DBA_TAB_COLS"), columnSql);
    }

    @Test
    void fetchTableColumns_dbaAccount_keepsDbaTabCols() throws Exception {
        CapturingConnection connection = new CapturingConnection(false);
        connection.routeRows("SYS.DBA_TAB_COLS", Collections.singletonList(columnRow("CUS_REGION", "REGION_CD", "VARCHAR", 0, "Y", 80L, 20L, null, "region code")));

        Map<String, List<RdbColumn>> columns = fetchTableColumns(connection, Collections.singletonList("CUS_REGION"));

        assertEquals("REGION_CD", columns.get("CUS_REGION").get(0).getName());
        String columnSql = connection.sqlOf("order by COLUMN_ID");
        assertNotNull(columnSql, "columns should be read: " + connection.issuedSql());
        assertTrue(columnSql.contains("SYS.DBA_TAB_COLS"), columnSql);
        assertFalse(columnSql.contains("SYS.ALL_TAB_COLUMNS"), columnSql);
        for (String sql : connection.issuedSql()) {
            assertFalse(sql.contains("SYS.ALL_TAB_COLUMNS"), "privileged accounts keep the DBA dictionary: " + sql);
        }
    }

    @Test
    void fetchTableColumns_resolvesDictionaryOncePerProvider() throws Exception {
        CapturingConnection connection = new CapturingConnection(true);
        DmMetaProviderDm provider = new DmMetaProviderDm(connection.proxy());

        invoke(provider, connection.proxy(), "fetchTableColumns", Collections.singletonList("CUS_REGION"));
        invoke(provider, connection.proxy(), "fetchTableColumns", Collections.singletonList("CUS_REGION"));

        assertEquals(1, connection.countSql("select 1 from SYS.DBA_TAB_COLS"), "the privilege check should run once: " + connection.issuedSql());
        assertEquals(2, connection.countSql("SYS.ALL_TAB_COLUMNS"), "both reads should use the ALL_ dictionary: " + connection.issuedSql());
    }

    @Test
    void fetchTableConstraints_dqlOnlyAccount_readsAllConstraints() throws Exception {
        CapturingConnection connection = new CapturingConnection(true);

        Map<String, List<ConstraintObject>> constraints = fetchTableConstraints(connection, Collections.singletonList("CUS_REGION"));

        assertTrue(constraints.isEmpty());
        String constraintSql = connection.sqlOf("CONSTRAINT_TYPE in");
        assertNotNull(constraintSql, "constraints should be read: " + connection.issuedSql());
        assertTrue(constraintSql.contains("ALL_CONSTRAINTS"), constraintSql);
        assertFalse(constraintSql.contains("DBA_CONSTRAINTS"), constraintSql);
    }

    @Test
    void selectTrigger_dqlOnlyAccount_readsAllTriggers() throws Exception {
        CapturingConnection connection = new CapturingConnection(true);
        connection.routeRows("ALL_TRIGGERS", Collections.singletonList(nameRow("TR_CUS_REGION")));

        List<Value> triggers = new DmMetaProviderDm(connection.proxy()).selectTrigger(SCHEMA);

        assertEquals(1, triggers.size());
        assertEquals("TR_CUS_REGION", triggers.get(0).asValue());
        String triggerSql = connection.sqlOf("TRIGGER_NAME");
        assertNotNull(triggerSql, "triggers should be read: " + connection.issuedSql());
        assertTrue(triggerSql.contains("ALL_TRIGGERS"), triggerSql);
        assertFalse(triggerSql.contains("DBA_TRIGGERS"), triggerSql);
    }

    @Test
    void selectSequences_dqlOnlyAccount_readsAllSequences() throws Exception {
        CapturingConnection connection = new CapturingConnection(true);
        connection.routeRows("all_sequences", Collections.singletonList(nameRow("SEQ_CUS_REGION")));

        List<Value> sequences = new DmMetaProviderDm(connection.proxy()).selectSequences(SCHEMA);

        assertEquals(1, sequences.size());
        assertEquals("SEQ_CUS_REGION", sequences.get(0).asValue());
        String sequenceSql = connection.sqlOf("SEQUENCE_NAME");
        assertNotNull(sequenceSql, "sequences should be read: " + connection.issuedSql());
        assertTrue(sequenceSql.contains("all_sequences"), sequenceSql);
        assertFalse(sequenceSql.contains("dba_sequences"), sequenceSql);
    }

    @Test
    void fetchTableColumns_nullableColumn_addsNoNonNullConstraint() throws Exception {
        CapturingConnection connection = new CapturingConnection(true);
        connection.routeRows("SYS.ALL_TAB_COLUMNS", Collections.singletonList(columnRow("CUS_REGION", "REGION_NM", "VARCHAR", 1, "Y", 80L, 20L, null, null)));

        RdbColumn column = fetchTableColumns(connection, Collections.singletonList("CUS_REGION")).get("CUS_REGION").get(0);

        assertTrue(column.getConstraints() == null || column.getConstraints().isEmpty(), "NULLABLE=Y carries no constraint");
        assertNull(column.getComment());
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private static Map<String, List<RdbColumn>> fetchTableColumns(CapturingConnection connection, List<String> tables) throws Exception {
        return (Map<String, List<RdbColumn>>) invoke(new DmMetaProviderDm(connection.proxy()), connection.proxy(), "fetchTableColumns", tables);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<ConstraintObject>> fetchTableConstraints(CapturingConnection connection, List<String> tables) throws Exception {
        return (Map<String, List<ConstraintObject>>) invoke(new DmMetaProviderDm(connection.proxy()), connection.proxy(), "fetchTableConstraints", tables);
    }

    private static Object invoke(DmMetaProviderDm provider, Connection connection, String name, List<String> tables) throws Exception {
        Method method = DmMetaProviderDm.class.getDeclaredMethod(name, Connection.class, String.class, String.class, List.class);
        method.setAccessible(true);
        try {
            return method.invoke(provider, connection, null, SCHEMA, tables);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }

    private static Map<String, Object> columnRow(String table, String column, String dataType, int columnId, String nullable, Long dataLength, Long charLength,
                                                 Integer dataPrecision, String comment) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("OWNER", SCHEMA);
        row.put("TABLE_NAME", table);
        row.put("COLUMN_NAME", column);
        row.put("DATA_TYPE", dataType);
        row.put("DATA_TYPE_OWNER", null);
        row.put("COLUMN_ID", columnId);
        row.put("DATA_LENGTH", dataLength);
        row.put("CHAR_LENGTH", charLength);
        row.put("DATA_PRECISION", dataPrecision);
        row.put("DATA_SCALE", null);
        row.put("NULLABLE", nullable);
        row.put("HIDDEN_COLUMN", "NO");
        row.put("IDENTITY_COLUMN", "NO");
        row.put("IS_IDENT", Boolean.FALSE);
        row.put("IDENT", null);
        row.put("IS_VIRT", Boolean.FALSE);
        row.put("COMMENTS", comment);
        row.put("DATA_DEFAULT", null);
        return row;
    }

    private static Map<String, Object> nameRow(String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("OBJECT_NAME", name);
        return row;
    }

    /**
     * Stands in for a DM connection. A {@code denyDbaDictionary} connection rejects {@code SYS.DBA_TAB_COLS} the way the
     * server does for an account without the DBA role: the failure happens while preparing the statement, before it runs.
     */
    private static final class CapturingConnection {

        private final List<String>                           issuedSql   = new ArrayList<>();
        private final Map<String, List<Map<String, Object>>> routes      = new LinkedHashMap<>();
        private final List<Map<String, Object>>              defaultRows = Collections.emptyList();
        private final boolean                                denyDbaDictionary;
        private final Connection                             proxy;

        private CapturingConnection(boolean denyDbaDictionary){
            this.denyDbaDictionary = denyDbaDictionary;
            ClassLoader loader = DmMetaProviderDmTest.class.getClassLoader();
            this.proxy = (Connection) Proxy.newProxyInstance(loader, new Class<?>[] { Connection.class }, this::invokeConnection);
        }

        private Connection proxy() {
            return this.proxy;
        }

        private List<String> issuedSql() {
            return this.issuedSql;
        }

        private void routeRows(String sqlFragment, List<Map<String, Object>> rows) {
            this.routes.put(sqlFragment, rows);
        }

        private int countSql(String fragment) {
            int count = 0;
            for (String sql : this.issuedSql) {
                if (sql.contains(fragment)) {
                    count++;
                }
            }
            return count;
        }

        /** @return the most recently issued statement whose text contains {@code fragment}, or {@code null}. */
        private String sqlOf(String fragment) {
            String found = null;
            for (String sql : this.issuedSql) {
                if (sql.contains(fragment)) {
                    found = sql;
                }
            }
            return found;
        }

        private List<Map<String, Object>> rowsFor(String sql) {
            for (Map.Entry<String, List<Map<String, Object>>> entry : this.routes.entrySet()) {
                if (sql.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
            return this.defaultRows;
        }

        private Object invokeConnection(Object proxy, Method method, Object[] args) throws SQLException {
            if ("prepareStatement".equals(method.getName()) && args != null && args.length > 0) {
                String sql = (String) args[0];
                this.issuedSql.add(sql);
                if (this.denyDbaDictionary && sql.contains("SYS.DBA_TAB_COLS")) {
                    throw new SQLException("没有[SYS.DBA_TAB_COLS]对象的查询权限", "42000");
                }
                return preparedStatement(rowsFor(sql));
            }
            return defaultReturn(method.getReturnType());
        }

        private PreparedStatement preparedStatement(List<Map<String, Object>> rows) {
            ClassLoader loader = DmMetaProviderDmTest.class.getClassLoader();
            return (PreparedStatement) Proxy.newProxyInstance(loader, new Class<?>[] { PreparedStatement.class }, (proxy, method, args) -> {
                if ("executeQuery".equals(method.getName())) {
                    return resultSet(rows);
                }
                if ("close".equals(method.getName())) {
                    return null;
                }
                return defaultReturn(method.getReturnType());
            });
        }

        private static ResultSet resultSet(List<Map<String, Object>> rows) {
            ClassLoader loader = DmMetaProviderDmTest.class.getClassLoader();
            return (ResultSet) Proxy.newProxyInstance(loader, new Class<?>[] { ResultSet.class }, new ResultSetHandler(rows));
        }
    }

    private static final class ResultSetHandler implements InvocationHandler {

        private final List<Map<String, Object>> rows;
        private int                             cursor = -1;

        private ResultSetHandler(List<Map<String, Object>> rows){
            this.rows = rows;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("next".equals(name)) {
                this.cursor++;
                return this.cursor < this.rows.size();
            }
            if ("getMetaData".equals(name)) {
                return metaData();
            }
            if ("wasNull".equals(name)) {
                return false;
            }
            if ("close".equals(name)) {
                return null;
            }
            if (name.startsWith("get") && args != null && args.length == 1) {
                Object value = currentRow().get(String.valueOf(args[0]));
                if (value == null) {
                    return defaultReturn(method.getReturnType());
                }
                if (method.getReturnType() == String.class) {
                    return String.valueOf(value);
                }
                if (method.getReturnType() == long.class || method.getReturnType() == Long.class) {
                    return ((Number) value).longValue();
                }
                if (method.getReturnType() == int.class || method.getReturnType() == Integer.class) {
                    return ((Number) value).intValue();
                }
                return value;
            }
            return defaultReturn(method.getReturnType());
        }

        private Map<String, Object> currentRow() {
            return this.rows.get(this.cursor);
        }

        private ResultSetMetaData metaData() {
            ClassLoader loader = DmMetaProviderDmTest.class.getClassLoader();
            return (ResultSetMetaData) Proxy.newProxyInstance(loader, new Class<?>[] { ResultSetMetaData.class }, (proxy, method, args) -> {
                if ("getColumnCount".equals(method.getName())) {
                    return 1;
                }
                if ("getColumnType".equals(method.getName())) {
                    return Types.VARCHAR;
                }
                if ("getColumnTypeName".equals(method.getName()) || "getColumnClassName".equals(method.getName())) {
                    return "text";
                }
                return defaultReturn(method.getReturnType());
            });
        }
    }

    private static Object defaultReturn(Class<?> returnType) {
        if (returnType == void.class) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == char.class) {
            return '\0';
        }
        if (returnType == double.class) {
            return 0.0d;
        }
        if (returnType == float.class) {
            return 0.0f;
        }
        return null;
    }
}
