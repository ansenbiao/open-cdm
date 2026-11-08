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
package com.clougence.clouddm.dsfamily.postgres.execute;

import java.sql.*;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.clougence.clouddm.dsfamily.postgres.definition.ui.editor.table.PgEditorProvider;
import com.clougence.clouddm.dsfamily.postgres.dialect.PostgreDialect;
import com.clougence.clouddm.sdk.execute.session.Session;
import com.clougence.clouddm.sdk.execute.session.rdb.DefaultRdbMetaService;
import com.clougence.clouddm.sdk.execute.session.rdb.DmRdbUmiService;
import com.clougence.clouddm.sdk.sql.SqlParserParameters;
import com.clougence.schema.editor.provider.SqlBuilder;
import com.clougence.schema.umi.struts.UmiTypes;
import com.clougence.sql.postgres.parser.PostgresVersion;
import com.clougence.utils.ExceptionUtils;
import com.clougence.utils.StringUtils;
import com.clougence.utils.jdbc.mapper.SingleValueRowMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * @author mode 2021/1/15 17:11
 */
@Slf4j
public class PgMetaService extends DefaultRdbMetaService {

    public PgMetaService(Session rdbSession){
        super(rdbSession);
    }

    @Override
    public Map<String, String> getSqlParserParameters() {
        String databaseVersion = this.fetchVersion("SELECT current_setting('server_version')");
        return Map.of(SqlParserParameters.VERSION, PostgresVersion.parse(databaseVersion).versionString());
    }

    @Override
    protected DmRdbUmiService rdbUmiService(Connection con) {
        return new PgUmiServiceDm(con);
    }

    @Override
    protected SqlBuilder getSqlBuilder() { return PgEditorProvider.INSTANCE; }

    @Override
    public String getCurrentCatalog() {
        try {
            return this.rdbSession.executeQuery(con -> {
                try (Statement s = con.createStatement(); ResultSet resultSet = s.executeQuery("select current_database()")) {
                    return ((SingleValueRowMapper<String>) (rs, columnType, columnTypeName, columnClassName) -> rs.getString(1)).mapRow(resultSet);
                }
            });
        } catch (Exception e) {
            String msg = "getCurrentCatalog failed, " + ExceptionUtils.getRootCauseMessage(e);
            log.error(msg, e);
            throw new RuntimeException(msg, e);
        }
    }

    @Override
    public String getCurrentSchema() {
        try {
            return this.rdbSession.executeQuery(con -> {
                try (Statement s = con.createStatement(); ResultSet resultSet = s.executeQuery("select current_schema()")) {
                    return ((SingleValueRowMapper<String>) (rs, columnType, columnTypeName, columnClassName) -> rs.getString(1)).mapRow(resultSet);
                }
            });
        } catch (Exception e) {
            String msg = "getCurrentSchema failed, " + ExceptionUtils.getRootCauseMessage(e);
            log.error(msg, e);
            throw new RuntimeException(msg, e);
        }

    }

    @Override
    public void testConnect() {
        try {
            int res = this.rdbSession.executeQuery(con -> {
                try (Statement s = con.createStatement(); ResultSet resultSet = s.executeQuery("select 1")) {
                    return ((SingleValueRowMapper<Integer>) (rs, columnType, columnTypeName, columnClassName) -> rs.getInt(1)).mapRow(resultSet);
                }
            });
            if (res != 1) {
                throw new SQLException("Test SQL 'select 1' failed.");
            }
        } catch (Exception e) {
            String msg = "testConnect failed, " + ExceptionUtils.getRootCauseMessage(e);
            log.error(msg, e);
            throw new RuntimeException(msg, e);
        }
    }

    @Override
    public List<String> requestObjectScript(Map<UmiTypes, Object> levelsParam, UmiTypes leafType, String leafName) {
        try {
            return this.rdbSession.executeQuery(con -> {
                switch (leafType) {
                    case Table:
                        return showCreateTable(con, StringUtils.toString(levelsParam.get(UmiTypes.Schema)), leafName);
                    case View:
                        return showCreateView(con, StringUtils.toString(levelsParam.get(UmiTypes.Catalog)), StringUtils.toString(levelsParam.get(UmiTypes.Schema)), leafName);
                    case Materialized:
                        return showCreateMaterializedView(con, StringUtils.toString(levelsParam.get(UmiTypes.Schema)), leafName);
                    case Trigger:
                        return showCreateTrigger(con, StringUtils.toString(levelsParam.get(UmiTypes.Catalog)), StringUtils.toString(levelsParam.get(UmiTypes.Schema)), leafName);
                    case Function:
                    case Procedure:
                        return showCreateFunction(con, StringUtils.toString(levelsParam.get(UmiTypes.Catalog)), StringUtils.toString(levelsParam.get(UmiTypes.Schema)), leafName);
                    case Sequence:
                        return showCreateSequence(con, StringUtils.toString(levelsParam.get(UmiTypes.Schema)), leafName);
                    default:
                        throw new UnsupportedOperationException("PostgreSQL '" + leafType + "' Unsupported.");
                }
            });
        } catch (Exception e) {
            String msg = "requestObjectScript error.msg:" + ExceptionUtils.getRootCauseMessage(e);
            log.error(msg, e);
            throw new RuntimeException(msg, e);
        }
    }

    protected List<String> showCreateFunction(Connection con, String catalog, String schema, String leafName) {
        String sql = "SELECT pg_get_functiondef(?) ";

        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, Integer.parseInt(leafName));

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Collections.singletonList(rs.getString(1));
                } else {
                    return Collections.emptyList();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    protected List<String> showCreateView(Connection con, String catalog, String schema, String view) throws SQLException {
        String showSql = "select pg_get_viewdef('" + PostgreDialect.INSTANCE.fmtTableName(true, catalog, schema, view) + "'::regclass, true)";

        try (Statement s = con.createStatement(); ResultSet rs = s.executeQuery(showSql)) {
            if (rs.next()) {
                return Collections.singletonList(rs.getString(1));
            } else {
                return Collections.emptyList();
            }
        }
    }

    /**
     * PostgreSQL has no SHOW CREATE TABLE, so the statement is recomposed from the catalog views pg_dump reads too:
     * every live column with its resolved type, nullability and default, followed by the table constraints.
     * Identity and generated columns surface through their pg_attrdef expression, which is also how a serial column
     * is stored, so the text stays executable for the common cases.
     */
    protected List<String> showCreateTable(Connection con, String schema, String table) throws SQLException {
        String showSql = "SELECT 'CREATE TABLE ' || quote_ident(n.nspname) || '.' || quote_ident(c.relname) || ' (' || chr(10) \n"
                         + "  || (SELECT string_agg('  ' || quote_ident(a.attname) || ' ' || format_type(a.atttypid, a.atttypmod) \n"
                         + "              || CASE WHEN a.attnotnull THEN ' NOT NULL' ELSE '' END \n"
                         + "              || CASE WHEN d.addef IS NULL THEN '' ELSE ' DEFAULT ' || d.addef END, ',' || chr(10) ORDER BY a.attnum) \n"
                         + "       FROM pg_attribute a \n" + "       LEFT JOIN (SELECT adrelid, adnum, pg_get_expr(adbin, adrelid) AS addef FROM pg_attrdef) d \n"
                         + "              ON d.adrelid = a.attrelid AND d.adnum = a.attnum \n" + "      WHERE a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped) \n"
                         + "  || COALESCE((SELECT ',' || chr(10) || string_agg('  CONSTRAINT ' || quote_ident(k.conname) || ' ' \n"
                         + "                                     || pg_get_constraintdef(k.oid), ',' || chr(10)) \n"
                         + "                FROM pg_constraint k WHERE k.conrelid = c.oid), '') \n" + "  || chr(10) || ');' \n"
                         + "  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace \n" + " WHERE n.nspname = ? AND c.relname = ? AND c.relkind IN ('r', 'p')";
        return singleValueScript(con, showSql, schema, table);
    }

    /**
     * pg_get_viewdef understands materialized views as well, so the body is returned bare exactly like the existing
     * view path does.
     */
    protected List<String> showCreateMaterializedView(Connection con, String schema, String materialized) throws SQLException {
        String showSql = "SELECT pg_get_viewdef(c.oid, true) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace \n"
                         + " WHERE c.relkind = 'm' AND n.nspname = ? AND c.relname = ?";
        return singleValueScript(con, showSql, schema, materialized);
    }

    /**
     * pg_sequence holds the options of every sequence since PostgreSQL 10; the clauses are emitted in the order the
     * CREATE SEQUENCE grammar expects.
     */
    protected List<String> showCreateSequence(Connection con, String schema, String sequence) throws SQLException {
        String showSql = "SELECT 'CREATE SEQUENCE ' || quote_ident(n.nspname) || '.' || quote_ident(c.relname) \n"
                         + "  || chr(10) || '    AS ' || format_type(s.seqtypid::oid, NULL) \n" + "  || chr(10) || '    INCREMENT BY ' || s.seqincrement \n"
                         + "  || chr(10) || '    MINVALUE ' || s.seqmin \n" + "  || chr(10) || '    MAXVALUE ' || s.seqmax \n"
                         + "  || chr(10) || '    START WITH ' || s.seqstart \n" + "  || chr(10) || '    CACHE ' || s.seqcache \n"
                         + "  || CASE WHEN s.seqcycle THEN chr(10) || '    CYCLE' ELSE '' END || chr(10) || ';' \n" + "  FROM pg_class c \n"
                         + "  JOIN pg_namespace n ON n.oid = c.relnamespace \n" + "  JOIN pg_sequence s ON s.seqrelid = c.oid \n"
                         + " WHERE c.relkind = 'S' AND n.nspname = ? AND c.relname = ?";
        return singleValueScript(con, showSql, schema, sequence);
    }

    private List<String> singleValueScript(Connection con, String showSql, String schema, String name) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(showSql)) {
            ps.setString(1, schema);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Collections.singletonList(rs.getString(1));
                } else {
                    return Collections.emptyList();
                }
            }
        }
    }

    private List<String> showCreateTrigger(Connection con, String catalog, String schema, String trigger) throws SQLException {
        String sql = "SELECT pg_get_triggerdef(t.oid, true) AS trigger_definition FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid\n"
                     + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE t.oid = ? and nspname = ?;";

        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, Integer.parseInt(trigger));
            ps.setString(2, schema);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Collections.singletonList(rs.getString(1));
                } else {
                    return Collections.emptyList();
                }
            }
        }
    }
}
