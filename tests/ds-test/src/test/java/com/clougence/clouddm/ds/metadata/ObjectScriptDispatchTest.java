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
package com.clougence.clouddm.ds.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.clougence.clouddm.ds.gauss.execute.gs.GsMetaService;
import com.clougence.clouddm.ds.gauss.execute.gsog.GsogMetaService;
import com.clougence.clouddm.dsfamily.db2.execute.Db2MetaService;
import com.clougence.clouddm.dsfamily.oracle.execute.OraMetaService;
import com.clougence.clouddm.dsfamily.postgres.execute.PgMetaService;
import com.clougence.clouddm.dsfamily.sqlserver.execute.MsSqlMetaService;
import com.clougence.clouddm.sdk.execute.session.Session;
import com.clougence.clouddm.sdk.execute.session.SessionCallback;
import com.clougence.clouddm.sdk.execute.session.rdb.DefaultRdbMetaService;
import com.clougence.clouddm.sdk.execute.session.rdb.DmRdbUmiService;
import com.clougence.schema.umi.struts.UmiTypes;

/**
 * Locks down the object definition dispatch of the dialects whose object tree lists more than tables.
 *
 * <p>The browse panel's definition viewer hands every leaf type of the tree to
 * {@link DefaultRdbMetaService#requestObjectScript(Map, UmiTypes, String)}, so a type the tree is able to list must not
 * fall into the {@code default} branch, and the catalog lookup has to bind the object name and its schema in the order
 * the statement expects. Both regress silently: an unsupported branch only fails once a user right-clicks, and swapped
 * parameters read back an empty definition.
 */
public class ObjectScriptDispatchTest {

    private static final String CATALOG    = "DEV";
    private static final String SCHEMA     = "BIZ";
    private static final String OBJECT     = "T_ORDER";
    private static final String DEFINITION = "-- definition";
    /** The PostgreSQL tree publishes routines and triggers under their pg_class oid rather than under their name. */
    private static final String PG_OID     = "16384";

    @Test
    void pg_readsEveryTypeItsTreePublishes() throws Exception {
        // anchors of the catalog reads, the same views pg_dump composes a table from
        ScriptFixture table = assertScript(PgMetaService::new, UmiTypes.Table, OBJECT, "pg_attribute", "pg_get_constraintdef");
        assertEquals(Arrays.asList(SCHEMA, OBJECT), table.binds.get(0), "a table is looked up by schema then name");

        assertScript(PgMetaService::new, UmiTypes.View, OBJECT, "pg_get_viewdef");
        ScriptFixture materialized = assertScript(PgMetaService::new, UmiTypes.Materialized, OBJECT, "relkind = 'm'");
        assertEquals(Arrays.asList(SCHEMA, OBJECT), materialized.binds.get(0));
        ScriptFixture sequence = assertScript(PgMetaService::new, UmiTypes.Sequence, OBJECT, "pg_sequence");
        assertEquals(Arrays.asList(SCHEMA, OBJECT), sequence.binds.get(0));

        assertScript(PgMetaService::new, UmiTypes.Procedure, PG_OID, "pg_get_functiondef");
        assertScript(PgMetaService::new, UmiTypes.Function, PG_OID, "pg_get_functiondef");
        ScriptFixture trigger = assertScript(PgMetaService::new, UmiTypes.Trigger, PG_OID, "pg_get_triggerdef");
        assertEquals(Arrays.asList(Integer.parseInt(PG_OID), SCHEMA), trigger.binds.get(0), "a trigger is looked up by oid");
    }

    @Test
    void pg_keepsSynonymUnsupportedBecauseItsTreeNeverListsOne() {
        assertUnsupported(PgMetaService::new, UmiTypes.Synonym, "PostgreSQL");
    }

    @Test
    void oracle_readsEveryTypeItsTreePublishes() throws Exception {
        // one dbms_metadata call per type, the package Oracle itself uses to describe an object
        ScriptFixture table = assertScript(OraMetaService::new, UmiTypes.Table, OBJECT, "dbms_metadata.get_ddl('TABLE'");
        assertEquals(Arrays.asList(OBJECT, SCHEMA), table.binds.get(0), "a table is looked up by name then owner");

        assertScript(OraMetaService::new, UmiTypes.View, OBJECT, "dbms_metadata.get_ddl('VIEW'");
        assertScript(OraMetaService::new, UmiTypes.Materialized, OBJECT, "MATERIALIZED_VIEW");
        assertScript(OraMetaService::new, UmiTypes.Sequence, OBJECT, "dbms_metadata.get_ddl('SEQUENCE'");
        assertScript(OraMetaService::new, UmiTypes.Procedure, OBJECT, "'PROCEDURE'");
        assertScript(OraMetaService::new, UmiTypes.Function, OBJECT, "'FUNCTION'");
        assertScript(OraMetaService::new, UmiTypes.Trigger, OBJECT, "'TRIGGER'");
        assertScript(OraMetaService::new, UmiTypes.Synonym, OBJECT, "'SYNONYM'");
    }

    /**
     * The Oracle tree offers more than the definition viewer can describe, and the object types left out are exactly
     * the ones whose answer depends on a scheduler version. They stay on the reported path, so the page is able to say
     * that this combination is not supported instead of failing.
     */
    @Test
    void oracle_keepsSchedulerAndAccountTypesUnsupported() {
        assertUnsupported(OraMetaService::new, UmiTypes.DBLink, "Oracle");
        assertUnsupported(OraMetaService::new, UmiTypes.Job, "Oracle");
        assertUnsupported(OraMetaService::new, UmiTypes.ROLE, "Oracle");
    }

    @Test
    void gauss_reusesThePostgresReadsAndKeepsItsOwnWrappers() throws Exception {
        // a table is recomposed by the PostgreSQL implementation, the catalogs behind it are shared by both families
        ScriptFixture table = assertScript(GsMetaService::new, UmiTypes.Table, OBJECT, "pg_attribute", "pg_get_constraintdef");
        assertEquals(Arrays.asList(SCHEMA, OBJECT), table.binds.get(0));
        assertScript(GsMetaService::new, UmiTypes.Materialized, OBJECT, "relkind = 'm'");

        // view and routine keep the GaussDB reading: the view is wrapped into a create statement, the body of a
        // routine is cut out of pg_get_functiondef
        ScriptFixture view = assertScript(GsMetaService::new, UmiTypes.View, OBJECT, "create view", "pg_get_viewdef");
        assertEquals(Collections.emptyList(), view.binds.get(0), "the GaussDB view statement is assembled without parameters");
        assertScript(GsMetaService::new, UmiTypes.Procedure, PG_OID, "pg_get_functiondef");
        assertScript(GsMetaService::new, UmiTypes.Function, PG_OID, "pg_get_functiondef");
    }

    /**
     * The sequence options of a PostgreSQL live in pg_sequence, a catalog openGauss does not provide, so GaussDB must
     * not inherit that read.
     */
    @Test
    void gauss_keepsSequenceUnsupportedBecauseOpenGaussHasNoPgSequence() {
        ScriptFixture fixture = new ScriptFixture();
        assertThrows(RuntimeException.class, () -> new GsMetaService(fixture.session()).requestObjectScript(levels(), UmiTypes.Sequence, OBJECT));
        assertTrue(fixture.sqls.isEmpty(), "no pg_sequence read should be issued against GaussDB: " + fixture.sqls);

        assertUnsupported(GsMetaService::new, UmiTypes.Sequence, "GaussDB");
        assertUnsupported(GsogMetaService::new, UmiTypes.Sequence, "GaussDBForOpenGauss");
    }

    @Test
    void sqlServer_readsEveryTypeItsTreePublishes() throws Exception {
        // no STRING_AGG, so the table body is aggregated with FOR XML PATH like SSMS does for the older editions
        ScriptFixture table = assertScript(MsSqlMetaService::new, UmiTypes.Table, OBJECT, "sys.columns", "FOR XML PATH", "sys.computed_columns");
        assertEquals(Arrays.asList(OBJECT, SCHEMA), table.binds.get(0), "a table is looked up by name then schema");

        assertScript(MsSqlMetaService::new, UmiTypes.View, OBJECT, "sp_helptext");
        ScriptFixture procedure = assertScript(MsSqlMetaService::new, UmiTypes.Procedure, OBJECT, "sys.sql_modules");
        assertTrue(procedure.sqls.get(0).contains("'P'"), "only procedures may match: " + procedure.sqls.get(0));
        assertScript(MsSqlMetaService::new, UmiTypes.Function, OBJECT, "sys.sql_modules");
        assertScript(MsSqlMetaService::new, UmiTypes.Trigger, OBJECT, "sys.triggers");
        assertScript(MsSqlMetaService::new, UmiTypes.Sequence, OBJECT, "sys.sequences");
        assertScript(MsSqlMetaService::new, UmiTypes.Synonym, OBJECT, "sys.synonyms");
    }

    @Test
    void sqlServer_keepsMaterializedUnsupportedBecauseItHasNoMaterializedView() {
        assertUnsupported(MsSqlMetaService::new, UmiTypes.Materialized, "SQLServer");
    }

    @Test
    void db2_readsEveryTypeItsTreePublishes() throws Exception {
        ScriptFixture view = assertScript(db2Service(), UmiTypes.View, OBJECT, "SYSCAT.VIEWS");
        assertEquals(Arrays.asList(SCHEMA, OBJECT), view.binds.get(0));
        // Db2 keeps the defining query of a materialized query table next to the one of a view
        assertScript(db2Service(), UmiTypes.Materialized, OBJECT, "SYSCAT.VIEWS");

        ScriptFixture sequence = assertScript(db2Service(), UmiTypes.Sequence, OBJECT, "SYSCAT.SEQUENCES");
        assertEquals(Arrays.asList(SCHEMA, OBJECT), sequence.binds.get(0));
        assertTrue(sequence.sqls.get(0).contains("SEQTYPE = 'S'"), "identity sequences are not user sequences: " + sequence.sqls.get(0));

        ScriptFixture procedure = assertScript(db2Service(), UmiTypes.Procedure, OBJECT, "SYSCAT.ROUTINES");
        assertTrue(procedure.sqls.get(0).contains("ROUTINETYPE = 'P'"), procedure.sqls.get(0));
        assertScript(db2Service(), UmiTypes.Function, OBJECT, "SYSCAT.ROUTINES");
        assertScript(db2Service(), UmiTypes.Trigger, OBJECT, "SYSCAT.TRIGGERS");
    }

    /**
     * Db2 for z/OS and Db2 for i both miss the LUW only {@code SYSPROC.DB2LK_GENERATE_DDL}, so a table definition has to
     * come from the same metadata reader the table editor uses rather than from a stored procedure.
     */
    @Test
    void db2_readsTableDefinitionFromTheMetadataReaderNotFromLuwDb2look() throws Exception {
        AtomicBoolean askedTree = new AtomicBoolean();
        ScriptFixture fixture = new ScriptFixture();

        List<String> script = new Db2TestMetaService(fixture.session(), askedTree).requestObjectScript(levels(), UmiTypes.Table, OBJECT);

        assertTrue(script.isEmpty(), "a table the metadata reader cannot describe should read back empty");
        assertTrue(askedTree.get(), "the table definition should be recomposed from the table metadata: " + fixture.sqls);
        for (String sql : fixture.sqls) {
            assertFalse(sql.contains("DB2LK_GENERATE_DDL"), "db2look is not available on this family: " + sql);
        }
    }

    @Test
    void db2_keepsSynonymUnsupportedBecauseItsTreeNeverListsOne() {
        assertUnsupported(db2Service(), UmiTypes.Synonym, "DB2");
    }

    // ------------------------------------------------------------------ harness

    private static Map<UmiTypes, Object> levels() {
        Map<UmiTypes, Object> levels = new HashMap<>();
        levels.put(UmiTypes.Catalog, CATALOG);
        levels.put(UmiTypes.Schema, SCHEMA);
        return levels;
    }

    private static Function<Session, Db2MetaService> db2Service() {
        return session -> new Db2TestMetaService(session, new AtomicBoolean());
    }

    /** Runs one dispatch, asserts the single catalog read it issues, and hands the recorded statements back. */
    private static ScriptFixture assertScript(Function<Session, ? extends DefaultRdbMetaService> serviceFactory, UmiTypes leafType, //
                                              String leafName, String... fragments) throws Exception {
        ScriptFixture fixture = new ScriptFixture();
        List<String> script = serviceFactory.apply(fixture.session()).requestObjectScript(levels(), leafType, leafName);

        assertEquals(Collections.singletonList(DEFINITION), script, leafType + " should hand the catalog text back");
        assertEquals(1, fixture.sqls.size(), leafType + " issued more than one statement: " + fixture.sqls);
        for (String fragment : fragments) {
            assertTrue(fixture.sqls.get(0).contains(fragment), leafType + " issued: " + fixture.sqls.get(0));
        }
        return fixture;
    }

    private static void assertUnsupported(Function<Session, ? extends DefaultRdbMetaService> serviceFactory, UmiTypes leafType, String dialect) {
        ScriptFixture fixture = new ScriptFixture();
        DefaultRdbMetaService metaService = serviceFactory.apply(fixture.session());

        RuntimeException failure = assertThrows(RuntimeException.class, () -> metaService.requestObjectScript(levels(), leafType, OBJECT));
        assertTrue(failure.getMessage().contains(dialect + " '" + leafType + "' Unsupported."), failure.getMessage());
    }

    /**
     * A session over a connection that records every statement and answers with one fixed text column, so a dialect
     * dispatch becomes observable without a database.
     */
    private static final class ScriptFixture {

        private final List<String>       sqls  = new ArrayList<>();
        private final List<List<Object>> binds = new ArrayList<>();

        private Session session() {
            return (Session) proxy(Session.class, (p, method, args) -> {
                if ("executeQuery".equals(method.getName()) && args != null && args.length == 1 && args[0] instanceof SessionCallback) {
                    return ((SessionCallback<?>) args[0]).doCallback(connection());
                }
                return defaultValue(method.getReturnType());
            });
        }

        private Connection connection() {
            return (Connection) proxy(Connection.class, (p, method, args) -> {
                String name = method.getName();
                if ("prepareStatement".equals(name)) {
                    return preparedStatement(record((String) args[0]));
                }
                if ("createStatement".equals(name)) {
                    return statement();
                }
                return defaultValue(method.getReturnType());
            });
        }

        private List<Object> record(String sql) {
            this.sqls.add(sql);
            List<Object> parameters = new ArrayList<>();
            this.binds.add(parameters);
            return parameters;
        }

        private Statement statement() {
            return (Statement) proxy(Statement.class, (p, method, args) -> {
                if ("executeQuery".equals(method.getName())) {
                    record((String) args[0]);
                    return resultSet();
                }
                return defaultValue(method.getReturnType());
            });
        }

        private PreparedStatement preparedStatement(List<Object> parameters) {
            return (PreparedStatement) proxy(PreparedStatement.class, (p, method, args) -> {
                String name = method.getName();
                if ("executeQuery".equals(name)) {
                    return resultSet();
                }
                if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer) {
                    int index = (Integer) args[0];
                    while (parameters.size() < index) {
                        parameters.add(null);
                    }
                    parameters.set(index - 1, args[1]);
                }
                return defaultValue(method.getReturnType());
            });
        }

        private ResultSet resultSet() {
            return (ResultSet) proxy(ResultSet.class, new InvocationHandler() {

                private boolean served;

                @Override
                public Object invoke(Object p, Method method, Object[] args) {
                    String name = method.getName();
                    if ("next".equals(name)) {
                        if (this.served) {
                            return false;
                        }
                        this.served = true;
                        return true;
                    }
                    if ("getString".equals(name)) {
                        return DEFINITION;
                    }
                    return defaultValue(method.getReturnType());
                }
            });
        }
    }

    /** Db2 has no callable definition of its own, the concrete variants differ only in the metadata reader. */
    private static final class Db2TestMetaService extends Db2MetaService {

        private final AtomicBoolean askedTree;

        private Db2TestMetaService(Session session, AtomicBoolean askedTree){
            super(session);
            this.askedTree = askedTree;
        }

        @Override
        protected DmRdbUmiService rdbUmiService(Connection con) {
            return (DmRdbUmiService) proxy(DmRdbUmiService.class, (p, method, args) -> {
                if ("detailLeaf".equals(method.getName())) {
                    this.askedTree.set(true);
                }
                return defaultValue(method.getReturnType());
            });
        }
    }

    private static Object proxy(Class<?> iface, InvocationHandler handler) {
        return Proxy.newProxyInstance(ObjectScriptDispatchTest.class.getClassLoader(), new Class<?>[] { iface }, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == float.class) {
            return 0F;
        }
        return 0;
    }
}
