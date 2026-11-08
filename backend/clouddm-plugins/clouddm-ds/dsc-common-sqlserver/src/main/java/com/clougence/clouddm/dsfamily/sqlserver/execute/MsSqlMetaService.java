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
package com.clougence.clouddm.dsfamily.sqlserver.execute;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.clougence.clouddm.dsfamily.sqlserver.definition.ui.editor.table.MsSqlEditorProvider;
import com.clougence.clouddm.dsfamily.sqlserver.dialect.SqlServerDialect;
import com.clougence.clouddm.sdk.execute.session.Session;
import com.clougence.clouddm.sdk.execute.session.rdb.DefaultRdbMetaService;
import com.clougence.clouddm.sdk.execute.session.rdb.DmRdbUmiService;
import com.clougence.clouddm.sdk.sql.SqlParserParameters;
import com.clougence.schema.editor.provider.SqlBuilder;
import com.clougence.schema.umi.struts.UmiTypes;
import com.clougence.sql.sqlserver.parser.SqlServerVersion;
import com.clougence.utils.ExceptionUtils;
import com.clougence.utils.StringUtils;
import com.clougence.utils.jdbc.mapper.SingleValueRowMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * @author mode 2021/1/15 17:11
 */
@Slf4j
public class MsSqlMetaService extends DefaultRdbMetaService {

    public MsSqlMetaService(Session rdbSession){
        super(rdbSession);
    }

    @Override
    public Map<String, String> getSqlParserParameters() {
        String databaseVersion = this.fetchVersion("SELECT CONVERT(VARCHAR(128), SERVERPROPERTY('ProductVersion'))");
        return Map.of(SqlParserParameters.VERSION, SqlServerVersion.parse(databaseVersion).versionString());
    }

    @Override
    protected DmRdbUmiService rdbUmiService(Connection con) {
        return new MsSqlUmiServiceDm(con);
    }

    @Override
    protected SqlBuilder getSqlBuilder() { return MsSqlEditorProvider.INSTANCE; }

    public String getCurrentCatalog() {
        try {
            return this.rdbSession.executeQuery(con -> {
                try (Statement s = con.createStatement(); ResultSet resultSet = s.executeQuery("select db_name()")) {
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
    public String getCurrentSchema() { return "dbo"; }

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
                    case Procedure:
                        return showCreateScript(con, MODULE_PROCEDURE_SQL, leafName, StringUtils.toString(levelsParam.get(UmiTypes.Schema)));
                    case Function:
                        return showCreateScript(con, MODULE_FUNCTION_SQL, leafName, StringUtils.toString(levelsParam.get(UmiTypes.Schema)));
                    case Trigger:
                        return showCreateScript(con, MODULE_TRIGGER_SQL, leafName, StringUtils.toString(levelsParam.get(UmiTypes.Schema)));
                    case Sequence:
                        return showCreateScript(con, SEQUENCE_SQL, leafName, StringUtils.toString(levelsParam.get(UmiTypes.Schema)));
                    case Synonym:
                        return showCreateScript(con, SYNONYM_SQL, leafName, StringUtils.toString(levelsParam.get(UmiTypes.Schema)));
                    default:
                        throw new UnsupportedOperationException("SQLServer '" + leafType + "' Unsupported.");
                }
            });
        } catch (Exception e) {
            String msg = "requestObjectScript error.msg:" + ExceptionUtils.getRootCauseMessage(e);
            log.error(msg, e);
            throw new RuntimeException(msg, e);
        }
    }

    private List<String> showCreateView(Connection con, String catalog, String schema, String view) throws SQLException {
        String showSql = "sp_helptext '" + SqlServerDialect.INSTANCE.fmtTableName(true, catalog, schema, view) + "'";

        try (Statement s = con.createStatement(); ResultSet rs = s.executeQuery(showSql)) {
            StringBuilder script = new StringBuilder();
            while (rs.next()) {
                script.append(rs.getString(1));
            }

            if (script.length() == 0) {
                return Collections.emptyList();
            } else {
                return Collections.singletonList(script.toString());
            }
        }
    }

    /**
     * sys.sql_modules keeps the original CREATE text of every SQL module, which is what the object tree wants to show
     * back. The object type is filtered on a constant list so a same-named view or check constraint cannot be picked
     * up instead.
     */
    private static final String MODULE_PROCEDURE_SQL = "SELECT m.definition FROM sys.sql_modules m JOIN sys.objects o ON o.object_id = m.object_id \n"
                                                       + " WHERE o.name = ? AND SCHEMA_NAME(o.schema_id) = ? AND o.type IN ('P', 'PC')";

    private static final String MODULE_FUNCTION_SQL  = "SELECT m.definition FROM sys.sql_modules m JOIN sys.objects o ON o.object_id = m.object_id \n"
                                                       + " WHERE o.name = ? AND SCHEMA_NAME(o.schema_id) = ? AND o.type IN ('FN', 'IF', 'TF', 'FS', 'AF')";

    // triggers live in their own view and are owned by the parent table, so the schema comes from the table.
    private static final String MODULE_TRIGGER_SQL   = "SELECT m.definition FROM sys.sql_modules m JOIN sys.triggers t ON t.object_id = m.object_id \n"
                                                       + " WHERE t.name = ? AND OBJECT_SCHEMA_NAME(t.parent_id) = ?";

    private static final String SEQUENCE_SQL         = "SELECT 'CREATE SEQUENCE ' + QUOTENAME(SCHEMA_NAME(s.schema_id)) + '.' + QUOTENAME(s.name) \n"
                                                       + "  + ' AS ' + QUOTENAME(TYPE_NAME(s.user_type_id)) \n"
                                                       + "  + ' START WITH ' + CAST(CAST(s.start_value AS bigint) AS varchar(20)) \n"
                                                       + "  + ' INCREMENT BY ' + CAST(CAST(s.increment AS bigint) AS varchar(20)) \n"
                                                       + "  + CASE WHEN s.minimum_value IS NULL THEN ' NO MINVALUE' \n"
                                                       + "         ELSE ' MINVALUE ' + CAST(CAST(s.minimum_value AS bigint) AS varchar(20)) END \n"
                                                       + "  + CASE WHEN s.maximum_value IS NULL THEN ' NO MAXVALUE' \n"
                                                       + "         ELSE ' MAXVALUE ' + CAST(CAST(s.maximum_value AS bigint) AS varchar(20)) END \n"
                                                       + "  + CASE WHEN s.is_cached = 0 THEN ' NO CACHE' \n" + "         ELSE ' CACHE ' + CAST(s.cache_size AS varchar(20)) END \n"
                                                       + "  + CASE WHEN s.is_cyclic = 1 THEN ' CYCLE' ELSE ' NO CYCLE' END \n"
                                                       + "  FROM sys.sequences s WHERE s.name = ? AND SCHEMA_NAME(s.schema_id) = ?";

    private static final String SYNONYM_SQL          = "SELECT 'CREATE SYNONYM ' + QUOTENAME(SCHEMA_NAME(s.schema_id)) + '.' + QUOTENAME(s.name) \n"
                                                       + "  + ' FOR ' + s.base_object_name \n" + "  FROM sys.synonyms s WHERE s.name = ? AND SCHEMA_NAME(s.schema_id) = ?";

    /**
     * SQL Server has no SHOW CREATE TABLE, so the statement is recomposed from the same catalog views SSMS reads.
     * FOR XML PATH is used instead of STRING_AGG because the aggregation must also work on SQL Server 2016 and
     * older, and the TYPE wrapper is required so that comparison operators inside a check or default definition are
     * returned unescaped. Computed columns carry no stored type of their own, so they are printed as their expression
     * instead; foreign keys and indexes stay out of the body because they are separate schema objects.
     */
    private static final String TABLE_SQL            = "SELECT 'CREATE TABLE ' + QUOTENAME(SCHEMA_NAME(o.schema_id)) + '.' + QUOTENAME(o.name) + ' (' + CHAR(10) \n"
                                                       + "  + ISNULL(STUFF((SELECT ',' + CHAR(10) + CASE WHEN c.is_computed = 1 \n"
                                                       + "                        THEN '    ' + QUOTENAME(c.name) + ' AS ' + cc.definition \n"
                                                       + "                             + CASE WHEN cc.is_persisted = 1 THEN ' PERSISTED' ELSE '' END \n"
                                                       + "                        ELSE '    ' + QUOTENAME(c.name) + ' ' + QUOTENAME(t.name) \n"
                                                       + "                             + CASE WHEN t.name IN ('decimal', 'numeric') \n"
                                                       + "                                    THEN '(' + CAST(c.precision AS varchar(10)) + ',' + CAST(c.scale AS varchar(10)) + ')' \n"
                                                       + "                                    WHEN t.name IN ('char', 'nchar', 'varchar', 'nvarchar', 'binary', 'varbinary') \n"
                                                       + "                                    THEN CASE WHEN c.max_length < 0 THEN '(max)' \n"
                                                       + "                                         ELSE '(' + CAST(CASE WHEN t.name IN ('nchar', 'nvarchar') \n"
                                                       + "                                                 THEN c.max_length / 2 ELSE c.max_length END AS varchar(10)) + ')' END \n"
                                                       + "                                    ELSE '' END \n" + "                             + CASE WHEN c.is_identity = 1 \n"
                                                       + "                                    THEN ' IDENTITY(' + CAST(CAST(idc.seed_value AS bigint) AS varchar(20)) + ',' \n"
                                                       + "                                         + CAST(CAST(idc.increment_value AS bigint) AS varchar(20)) + ')' \n"
                                                       + "                                    ELSE '' END \n"
                                                       + "                             + CASE WHEN dc.definition IS NULL THEN '' \n"
                                                       + "                                    ELSE ' CONSTRAINT ' + QUOTENAME(dc.name) + ' DEFAULT ' + dc.definition END \n"
                                                       + "                             + CASE WHEN c.is_nullable = 1 THEN ' NULL' ELSE ' NOT NULL' END \n"
                                                       + "                        END \n" + "            FROM sys.columns c \n"
                                                       + "            JOIN sys.types t ON t.user_type_id = c.user_type_id \n"
                                                       + "            LEFT JOIN sys.computed_columns cc ON cc.object_id = c.object_id AND cc.column_id = c.column_id \n"
                                                       + "            LEFT JOIN sys.identity_columns idc ON idc.object_id = c.object_id AND idc.column_id = c.column_id \n"
                                                       + "            LEFT JOIN sys.default_constraints dc ON dc.object_id = c.default_object_id \n"
                                                       + "            WHERE c.object_id = o.object_id ORDER BY c.column_id \n"
                                                       + "            FOR XML PATH(''), TYPE).value('.', 'nvarchar(max)'), 1, 1, ''), '') \n"
                                                       + "  + ISNULL(STUFF((SELECT ',' + CHAR(10) + '    CONSTRAINT ' + QUOTENAME(k.name) + ' ' \n"
                                                       + "                   + CASE k.type_desc WHEN 'PRIMARY_KEY_CONSTRAINT' THEN 'PRIMARY KEY' ELSE 'UNIQUE' END + ' (' \n"
                                                       + "                   + STUFF((SELECT ',' + QUOTENAME(ac.name) \n"
                                                       + "                            FROM sys.index_columns ic \n"
                                                       + "                            JOIN sys.columns ac ON ac.object_id = ic.object_id AND ac.column_id = ic.column_id \n"
                                                       + "                            WHERE ic.object_id = i.object_id AND ic.index_id = i.index_id \n"
                                                       + "                              AND ic.is_included_column = 0 ORDER BY ic.key_ordinal \n"
                                                       + "                            FOR XML PATH(''), TYPE).value('.', 'nvarchar(max)'), 1, 1, '') + ')' \n"
                                                       + "            FROM sys.key_constraints k \n"
                                                       + "            JOIN sys.indexes i ON i.object_id = k.parent_object_id AND i.index_id = k.unique_index_id \n"
                                                       + "            WHERE k.parent_object_id = o.object_id ORDER BY k.name \n"
                                                       + "            FOR XML PATH(''), TYPE).value('.', 'nvarchar(max)'), 1, 1, ''), '') \n"
                                                       + "  + ISNULL(STUFF((SELECT ',' + CHAR(10) + '    CONSTRAINT ' + QUOTENAME(ck.name) + ' CHECK ' + ck.definition \n"
                                                       + "            FROM sys.check_constraints ck \n" + "            WHERE ck.parent_object_id = o.object_id ORDER BY ck.name \n"
                                                       + "            FOR XML PATH(''), TYPE).value('.', 'nvarchar(max)'), 1, 1, ''), '') \n" + "  + CHAR(10) + ');' \n"
                                                       + "  FROM sys.tables o WHERE o.name = ? AND SCHEMA_NAME(o.schema_id) = ?";

    protected List<String> showCreateTable(Connection con, String schema, String table) throws SQLException {
        return showCreateScript(con, TABLE_SQL, table, schema);
    }

    /**
     * Every statement above binds the object name first and its schema second, so one helper covers all of them.
     */
    private List<String> showCreateScript(Connection con, String showSql, String name, String schema) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(showSql)) {
            ps.setString(1, name);
            ps.setString(2, schema);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String script = rs.getString(1);
                    if (StringUtils.isBlank(script)) {
                        return Collections.emptyList();
                    }
                    return Collections.singletonList(script);
                } else {
                    return Collections.emptyList();
                }
            }
        }
    }
}
