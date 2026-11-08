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
package com.clougence.clouddm.base.metadata.ds;

import java.sql.JDBCType;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public final class ColMetaData {
    // Complete JSON object whose leaf paths identify source fields; stored through the VARCHAR value path.
    public static final String JSON_DOCUMENT = "JSON_DOCUMENT";

    private String             catalog;
    private String             schema;
    private String             table;
    /** Name inside the result, unique for every column of it. */
    private String             column;
    /** Name as the data source reported it, which several columns can share. Only set when it differs from {@link #column}. */
    private String             sourceColumn;
    /** Position of the column inside the statement, counted from one. */
    private int                index;

    private String             columnType;
    private JDBCType           jdbcType;
    private int                precision;

    /**
     * The column of the data source behind this column of the result, which is what a rule or a statement about the data
     * source has to name, as opposed to {@link #column} which only names the result itself.
     */
    public String realColumn() {
        return this.sourceColumn == null || this.sourceColumn.isEmpty() ? this.column : this.sourceColumn;
    }
}
