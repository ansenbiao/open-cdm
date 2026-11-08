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

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.clougence.clouddm.base.metadata.ds.ColMetaData;
import com.clougence.clouddm.file.convert.config.xlsx.XlsxOption;

/**
 * The cached result and the option of the page are both walked by position, so the plan must stay right when the
 * column index of the metadata says something else.
 */
public class MsExcelExportPlanTest {

    /**
     * A statement may name two result columns the same way, and those are cached under one name. The column that
     * survives still reports the position it had in the statement, which is behind the count of the cached columns.
     */
    @Test
    public void repeatedResultColumnNameDoesNotPushTheOptionBehindItsEnd() {
        List<ColMetaData> stored = new ArrayList<>();
        for (int i = 1; i <= 113; i++) {
            stored.add(column("c_" + i, i));
        }
        // 'id' was read at position 3 and again at position 115, and only the last one is kept
        stored.add(column("id", 115));

        MsExcelExportPlan plan = MsExcelExportPlan.of(stored, option(114, true));

        assertEquals(114, plan.getColumns().size());
        assertEquals(Integer.valueOf(113), plan.getColumns().get(113));
        assertEquals(114, plan.getHeader().size());
        assertEquals("option_114", plan.getHeader().get(113));
    }

    /** Data sources that are not reached over JDBC number their columns from zero. */
    @Test
    public void zeroBasedMetadataIndexStillStartsAtTheFirstColumn() {
        List<ColMetaData> stored = Arrays.asList(column("key", 0), column("value", 1), column("partition", 2));

        MsExcelExportPlan plan = MsExcelExportPlan.of(stored, option(3, true));

        assertEquals(Arrays.asList(0, 1, 2), plan.getColumns());
        assertEquals(Arrays.asList("option_1", "option_2", "option_3"), plan.getHeader());
    }

    @Test
    public void droppedColumnsLeaveTheKeptOnesAtTheirOwnValues() {
        List<ColMetaData> stored = Arrays.asList(column("id", 1), column("name", 2), column("salary", 3));

        XlsxOption option = option(3, true);
        option.getColumns().get(1).setExport(false);
        MsExcelExportPlan plan = MsExcelExportPlan.of(stored, option);

        assertEquals(Arrays.asList(0, 2), plan.getColumns());
        assertEquals(Arrays.asList("option_1", "option_3"), plan.getHeader());
        assertEquals(0, plan.storedColumn(0));
        assertEquals(2, plan.storedColumn(1));
        assertEquals(-1, plan.storedColumn(2));
    }

    /** The page lets the title of a column be rewritten, which may not change the values that are written. */
    @Test
    public void renamedTitleKeepsItsOwnStoredColumn() {
        List<ColMetaData> stored = Arrays.asList(column("id", 1), column("id", 2));

        XlsxOption option = option(2, true);
        option.getColumns().get(0).setColumnName("first id");
        option.getColumns().get(1).setColumnName("");
        MsExcelExportPlan plan = MsExcelExportPlan.of(stored, option);

        assertEquals(Arrays.asList("first id", "id"), plan.getHeader());
        assertEquals(Arrays.asList(0, 1), plan.getColumns());
    }

    /** An option that does not describe every column of the result must not hide or break the remaining columns. */
    @Test
    public void optionShorterThanTheResultStillExportsEveryColumn() {
        List<ColMetaData> stored = Arrays.asList(column("id", 1), column("name", 2), column("salary", 3));

        MsExcelExportPlan plan = MsExcelExportPlan.of(stored, option(2, true));

        assertEquals(Arrays.asList(0, 1, 2), plan.getColumns());
        assertEquals(Arrays.asList("option_1", "option_2", "salary"), plan.getHeader());
    }

    @Test
    public void missingOptionExportsTheWholeResult() {
        List<ColMetaData> stored = Collections.singletonList(column("id", 1));

        MsExcelExportPlan plan = MsExcelExportPlan.of(stored, new XlsxOption());

        assertEquals(Collections.singletonList(0), plan.getColumns());
        assertEquals(Collections.singletonList("id"), plan.getHeader());
    }

    private static ColMetaData column(String name, int index) {
        ColMetaData colMetaData = new ColMetaData();
        colMetaData.setColumn(name);
        colMetaData.setIndex(index);
        return colMetaData;
    }

    private static XlsxOption option(int columnCount, boolean export) {
        List<XlsxOption.ColumnOption> columns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            XlsxOption.ColumnOption columnOption = new XlsxOption.ColumnOption();
            columnOption.setColumnName("option_" + i);
            columnOption.setExport(export);
            columns.add(columnOption);
        }

        XlsxOption option = new XlsxOption();
        option.setColumns(columns);
        return option;
    }
}
