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
package com.clougence.clouddm.dsfamily.db2.execute;

import java.sql.*;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.clougence.clouddm.dsfamily.db2.definition.ui.editor.table.Db2EditorProvider;
import com.clougence.clouddm.sdk.execute.session.Session;
import com.clougence.clouddm.sdk.execute.session.rdb.DefaultRdbMetaService;
import com.clougence.clouddm.sdk.sql.SqlParserParameters;
import com.clougence.schema.editor.EditorHelperDm;
import com.clougence.schema.editor.domain.ETable;
import com.clougence.schema.editor.provider.SqlBuilder;
import com.clougence.schema.editor.triggers.TriggerContext;
import com.clougence.schema.umi.service.RdbUmiServiceDm;
import com.clougence.schema.umi.struts.UmiTypes;
import com.clougence.sql.db2.parser.Db2Version;
import com.clougence.utils.ExceptionUtils;
import com.clougence.utils.StringUtils;
import com.clougence.utils.jdbc.mapper.SingleValueRowMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * @author mode 2021/1/15 17:11
 */
@Slf4j
public abstract class Db2MetaService extends DefaultRdbMetaService {

    public Db2MetaService(Session rdbSession){
        super(rdbSession);
    }

    @Override
    public Map<String, String> getSqlParserParameters() {
        String databaseVersion = this.fetchVersion("SELECT PROD_RELEASE FROM SYSIBMADM.ENV_PROD_INFO");
        return Map.of(SqlParserParameters.VERSION, Db2Version.parse(databaseVersion).versionString());
    }

    @Override
    protected SqlBuilder getSqlBuilder() { return Db2EditorProvider.INSTANCE; }

    @Override
    public String getCurrentCatalog() {
        try {
            return this.rdbSession.executeQuery(con -> {
                try (Statement s = con.createStatement(); ResultSet resultSet = s.executeQuery("VALUES CURRENT SERVER")) {
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
                try (Statement s = con.createStatement(); ResultSet resultSet = s.executeQuery("VALUES CURRENT SCHEMA")) {
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
                try (Statement s = con.createStatement(); ResultSet resultSet = s.executeQuery("VALUES 1")) {
                    return ((SingleValueRowMapper<Integer>) (rs, columnType, columnTypeName, columnClassName) -> rs.getInt(1)).mapRow(resultSet);
                }
            });
            if (res != 1) {
                throw new SQLException("Test SQL 'select 1 from sysibm.sysdummy1' failed.");
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
                String schema = StringUtils.toString(levelsParam.get(UmiTypes.Schema));
                switch (leafType) {
                    case Table:
                        return requestTableScript(con, StringUtils.toString(levelsParam.get(UmiTypes.Catalog)), schema, leafName);
                    case View:
                    case Materialized:
                        // SYSCAT.VIEWS keeps the defining query of both views and materialized query tables
                        String showViewSql = "SELECT TEXT FROM SYSCAT.VIEWS WHERE VIEWSCHEMA = ? AND VIEWNAME = ? ";
                        return showCreateObject(con, showViewSql, schema, leafName);
                    case Sequence:
                        String showSequenceSql = "SELECT 'CREATE SEQUENCE ' || SEQSCHEMA || '.' || SEQNAME \n" + "  || ' START WITH ' || CHAR(START) \n"
                                                 + "  || ' INCREMENT BY ' || CHAR(INCREMENT) \n"
                                                 + "  || CASE WHEN MINVALUE IS NULL THEN ' NO MINVALUE' ELSE ' MINVALUE ' || CHAR(MINVALUE) END \n"
                                                 + "  || CASE WHEN MAXVALUE IS NULL THEN ' NO MAXVALUE' ELSE ' MAXVALUE ' || CHAR(MAXVALUE) END \n"
                                                 + "  || CASE WHEN CYCLE = 'Y' THEN ' CYCLE' ELSE ' NO CYCLE' END \n"
                                                 + "FROM SYSCAT.SEQUENCES WHERE SEQTYPE = 'S' AND SEQSCHEMA = ? AND SEQNAME = ? ";
                        return showCreateObject(con, showSequenceSql, schema, leafName);
                    case Procedure:
                        String showProcedureSql = "SELECT TEXT FROM SYSCAT.ROUTINES WHERE ROUTINETYPE = 'P' AND ROUTINESCHEMA = ? AND SPECIFICNAME = ? ";
                        return showCreateObject(con, showProcedureSql, schema, leafName);
                    case Function:
                        String showFunctionSql = "SELECT TEXT FROM SYSCAT.ROUTINES WHERE ROUTINETYPE = 'F' AND ROUTINESCHEMA = ? AND SPECIFICNAME = ? ";
                        return showCreateObject(con, showFunctionSql, schema, leafName);
                    case Trigger:
                        String showTriggerSql = "SELECT TEXT FROM SYSCAT.TRIGGERS WHERE TRIGSCHEMA = ? AND TRIGNAME = ? ";
                        return showCreateObject(con, showTriggerSql, schema, leafName);
                    default:
                        throw new UnsupportedOperationException("DB2 '" + leafType + "' Unsupported.");
                }
            });
        } catch (Exception e) {
            String msg = "requestObjectScript error.msg:" + ExceptionUtils.getRootCauseMessage(e);
            log.error(msg, e);
            throw new RuntimeException(msg, e);
        }
        //        return super.requestObjectScript(levelsParam, leafType, leafName);
    }

    private List<String> showCreateObject(Connection con, String showSql, String schema, String name) throws SQLException {
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

    /**
     * Db2 has no SHOW CREATE TABLE, and the LUW helper SYSPROC.DB2LK_GENERATE_DDL is absent on both platforms this
     * family serves (Db2 for z/OS and Db2 for i), so the statement is recomposed with the same metadata reader the
     * table editor uses. Whatever the tree is able to list, this is able to print back.
     */
    private List<String> requestTableScript(Connection con, String catalog, String schema, String table) throws Exception {
        RdbUmiServiceDm umiService = rdbUmiService(con);
        EditorHelperDm editorHelper = new EditorHelperDm(umiService, this.getSqlBuilder());
        ETable eTable = editorHelper.loadTable(catalog, schema, table);
        if (eTable == null) {
            return Collections.emptyList();
        }
        TriggerContext buildContext = new TriggerContext();
        buildContext.setUseDelimited(true);
        return this.getSqlBuilder().tableCreate(buildContext, catalog, schema, table, eTable);
    }
}
