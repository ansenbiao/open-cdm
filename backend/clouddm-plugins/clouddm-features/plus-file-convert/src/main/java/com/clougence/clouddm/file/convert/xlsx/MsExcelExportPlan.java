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
package com.clougence.clouddm.file.convert.xlsx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.clougence.clouddm.base.metadata.ds.ColMetaData;
import com.clougence.clouddm.file.convert.config.xlsx.XlsxOption;
import com.clougence.utils.StringUtils;

import lombok.Getter;

/**
 * Tells which columns of the cached result set are written to the sheet and under which title.
 * <p>
 * The export option of the page describes the columns in the order they were received, and the cached rows store the
 * values in exactly that order, so both are matched by position. The column index carried by the metadata is the
 * position inside the statement of the data source: it counts columns that share one name, and non relational data
 * sources do not even start it at one, so it must not be used to walk the option.
 */
@Getter
public final class MsExcelExportPlan {

    /** Positions inside the stored result columns, in the order of the sheet. */
    private final List<Integer> columns;
    /** Title of every exported column, in the order of the sheet. */
    private final List<String>  header;

    private MsExcelExportPlan(List<Integer> columns, List<String> header){
        this.columns = Collections.unmodifiableList(columns);
        this.header = Collections.unmodifiableList(header);
    }

    public static MsExcelExportPlan of(List<ColMetaData> storedColumns, XlsxOption option) {
        List<XlsxOption.ColumnOption> optionColumns = option == null ? null : option.getColumns();

        List<Integer> columns = new ArrayList<>(storedColumns.size());
        List<String> header = new ArrayList<>(storedColumns.size());
        for (int i = 0; i < storedColumns.size(); i++) {
            XlsxOption.ColumnOption columnOption = optionColumns == null || i >= optionColumns.size() ? null : optionColumns.get(i);
            if (columnOption != null && !columnOption.isExport()) {
                continue;
            }

            columns.add(i);
            // A column without a title of its own, which happens when the option does not cover the result, keeps the
            // name the data source reported.
            header.add(columnOption == null || StringUtils.isBlank(columnOption.getColumnName()) //
                ? storedColumns.get(i).getColumn() : columnOption.getColumnName());
        }

        return new MsExcelExportPlan(columns, header);
    }

    public int size() {
        return this.columns.size();
    }

    /**
     * Position inside the stored result columns of the given sheet column, or -1 when the sheet column was not planned.
     */
    public int storedColumn(int sheetColumn) {
        return sheetColumn < 0 || sheetColumn >= this.columns.size() ? -1 : this.columns.get(sheetColumn);
    }
}
