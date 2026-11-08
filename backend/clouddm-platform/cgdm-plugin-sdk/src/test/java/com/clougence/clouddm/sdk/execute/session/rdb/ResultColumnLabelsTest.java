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
package com.clougence.clouddm.sdk.execute.session.rdb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * A result is held together by the name of its columns, so the names have to be enough to keep every column of it.
 */
public class ResultColumnLabelsTest {

    @Test
    public void resultWithoutRepeatedNameKeepsItsNamesAsTheyAre() {
        List<String> reported = Arrays.asList("id", "name", "amount");

        assertEquals(reported, ResultColumnLabels.unique(reported));
    }

    /** The first column of a repeated name has to keep the name, because that is the name the statement gave to it. */
    @Test
    public void firstColumnOfARepeatedNameKeepsTheNameOfTheStatement() {
        List<String> reported = Arrays.asList("id", "amount", "name", "amount");

        List<String> names = ResultColumnLabels.unique(reported);

        assertEquals(Arrays.asList("id", "amount", "name", "amount_2"), names);
    }

    @Test
    public void threeColumnsOfOneNameAreNumberedInOrder() {
        List<String> reported = Arrays.asList("amount", "amount", "amount", "amount");

        assertEquals(Arrays.asList("amount", "amount_2", "amount_3", "amount_4"), ResultColumnLabels.unique(reported));
    }

    /** A generated name may not take the place of a name the statement reports itself. */
    @Test
    public void generatedNameDoesNotTakeANameTheStatementReports() {
        List<String> reported = Arrays.asList("amount", "amount", "amount_2");

        List<String> names = ResultColumnLabels.unique(reported);

        assertEquals(Arrays.asList("amount", "amount_3", "amount_2"), names);
        assertEquals(3, new HashSet<>(names).size());
    }

    /** The names of a statement that reads the whole of a table twice, which is how a data source repeats names by itself. */
    @Test
    public void everyColumnOfTheResultIsKept() {
        List<String> reported = new ArrayList<>();
        for (int i = 1; i <= 114; i++) {
            reported.add("c_" + i);
        }
        // 'id' is reported by both halves of 'select t.*, t.* from t', so the statement has 116 columns
        reported.add(3, "id");
        reported.add("id");

        List<String> names = ResultColumnLabels.unique(reported);

        assertEquals(116, names.size());
        assertEquals(116, new HashSet<>(names).size());
        assertEquals("id", names.get(3));
        assertEquals("id_2", names.get(115));
    }

    @Test
    public void nameThatTheDataSourceDoesNotGiveIsLeftAlone() {
        List<String> reported = Arrays.asList(null, null);

        assertEquals(reported, ResultColumnLabels.unique(reported));
    }

    @Test
    public void noColumnAtAllIsNoNameAtAll() {
        assertEquals(Collections.emptyList(), ResultColumnLabels.unique(null));
        assertEquals(Collections.emptyList(), ResultColumnLabels.unique(Collections.emptyList()));
    }

    /** The names are given out in the order of the statement, so the position of a column never moves. */
    @Test
    public void orderOfTheStatementIsKept() {
        List<String> reported = Arrays.asList("b", "a", "b", "a");

        List<String> names = ResultColumnLabels.unique(reported);

        assertEquals(Arrays.asList("b", "a", "b_2", "a_2"), names);
        for (int i = 0; i < reported.size(); i++) {
            String reportedName = reported.get(i);
            assertEquals(reportedName, names.get(i).substring(0, reportedName.length()));
        }
    }

    /** Only a name which several columns share stops being enough to address one of them. */
    @Test
    public void nameOfOneColumnAloneIsNotDuplicated() {
        List<String> reported = Arrays.asList("id", "name", "amount");

        assertEquals(Collections.emptySet(), ResultColumnLabels.duplicated(reported));
    }

    @Test
    public void nameOfSeveralColumnsIsDuplicated() {
        List<String> reported = Arrays.asList("phone", "name", "phone", "email");

        assertEquals(new HashSet<>(Arrays.asList("phone")), ResultColumnLabels.duplicated(reported));
    }

    /**
     * A caller which holds information about one column by its name has to drop exactly the names which get numbered,
     * because those are the names which no longer address one column of the result.
     */
    @Test
    public void onlyDuplicatedNamesAreNumbered() {
        List<String> reported = Arrays.asList("phone", "name", "phone", "email");

        List<String> names = ResultColumnLabels.unique(reported);
        Set<String> duplicated = ResultColumnLabels.duplicated(reported);

        assertEquals(new HashSet<>(Arrays.asList("phone")), duplicated);
        for (int i = 0; i < reported.size(); i++) {
            if (duplicated.contains(reported.get(i))) {
                assertTrue(names.get(i), names.get(i).startsWith(reported.get(i)));
            } else {
                assertEquals(reported.get(i), names.get(i));
            }
        }
        assertEquals(reported.size(), new HashSet<>(names).size());
    }

    @Test
    public void nothingReportedIsNothingDuplicated() {
        assertEquals(Collections.emptySet(), ResultColumnLabels.duplicated(null));
        assertEquals(Collections.emptySet(), ResultColumnLabels.duplicated(Collections.emptyList()));
        assertEquals(Collections.emptySet(), ResultColumnLabels.duplicated(Arrays.asList(null, null)));
    }
}
