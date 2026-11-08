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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * A column that the result had to name of its own is beyond the reach of the driver, which answers a name with the first
 * column that carries it, so the view has to answer it with the position the column was reported at.
 */
public class UniqueLabelResultSetTest {

    /** The view the session builds for a statement that reports 'amount' twice. */
    @Test
    public void everyColumnOfARepeatedNameIsReadAtItsOwnPosition() throws SQLException {
        List<String> reported = Arrays.asList("amount", "amount", "id");
        ResultSet rs = UniqueLabelResultSet.wrap(this.driver(reported, Arrays.asList(100, 200, 7)), this.labelToIndex(reported));

        assertEquals("100", rs.getString("amount"));
        assertEquals("200", rs.getString("amount_2"));
        assertEquals("7", rs.getString("id"));
    }

    /** What the view is needed for: on its own the driver has no name for the second column of a repeated name. */
    @Test
    public void driverAloneCannotReachTheSecondColumnOfARepeatedName() throws SQLException {
        ResultSet rs = this.driver(Arrays.asList("amount", "amount"), Arrays.asList(100, 200));

        assertEquals("100", rs.getString("amount"));
        try {
            rs.getString("amount_2");
            fail("the driver does not know a name that the statement did not report");
        } catch (SQLException expected) {
            assertEquals("no such column: amount_2", expected.getMessage());
        }
    }

    /** A name the view was not given is left to the driver, which is what keeps its own lookups working. */
    @Test
    public void nameThatWasNotRenamedIsLeftToTheDriver() throws SQLException {
        ResultSet rs = UniqueLabelResultSet.wrap(this.driver(Arrays.asList("amount", "amount"), Arrays.asList(100, 200)), this.labelToIndex(Arrays.asList("amount", "amount")));

        assertEquals("100", rs.getString("amount"));
    }

    @Test
    public void positionOfARenamedColumnIsAnsweredForItsName() throws SQLException {
        ResultSet rs = UniqueLabelResultSet.wrap(this.driver(Arrays.asList("amount", "amount"), Arrays.asList(100, 200)), this.labelToIndex(Arrays.asList("amount", "amount")));

        assertEquals(2, rs.findColumn("amount_2"));
        assertEquals(1, rs.findColumn("amount"));
    }

    @Test
    public void argumentsBehindTheColumnStayInPlace() throws SQLException {
        ResultSet rs = UniqueLabelResultSet.wrap(this.driver(Arrays.asList("amount", "amount"), Arrays.asList(100, 200)), this.labelToIndex(Arrays.asList("amount", "amount")));

        assertEquals("200", rs.getObject("amount_2", BigDecimal.class));
    }

    /** Reading a row on and asking what the last read was are the driver's own business. */
    @Test
    public void rowOfTheResultIsReadThroughTheDriver() throws SQLException {
        ResultSet rs = UniqueLabelResultSet.wrap(this.driver(Arrays.asList("amount", "amount"), Arrays.asList(100, 200)), this.labelToIndex(Arrays.asList("amount", "amount")));

        assertEquals(true, rs.next());
        assertEquals(true, rs.wasNull());
    }

    @Test
    public void errorOfTheDriverIsTheErrorOfTheView() throws SQLException {
        ResultSet rs = UniqueLabelResultSet.wrap(this.driver(Arrays.asList("amount", "amount"), Arrays.asList(100, 200)), this.labelToIndex(Arrays.asList("amount", "amount")));

        try {
            rs.getString("nothing");
            fail("the error of the driver has to reach the reader of the result");
        } catch (SQLException expected) {
            assertEquals("no such column: nothing", expected.getMessage());
        }
    }

    /** A result without a repeated name is read as it always was, without a view in between. */
    @Test
    public void resultWithoutRepeatedNameIsLeftUntouched() {
        ResultSet rs = this.driver(Arrays.asList("amount", "id"), Arrays.asList(100, 7));

        assertSame(rs, UniqueLabelResultSet.wrap(rs, this.labelToIndex(Arrays.asList("amount", "id"))));
        assertSame(rs, UniqueLabelResultSet.wrap(rs, Collections.emptyMap()));
    }

    // --- helpers ---

    /**
     * The names that the result had to give to itself, and the position behind each of them, which is what the session
     * hands to the view.
     */
    private Map<String, Integer> labelToIndex(List<String> reported) {
        List<String> names = ResultColumnLabels.unique(reported);
        Map<String, Integer> labelToIndex = new HashMap<>();
        for (int i = 0; i < names.size(); i++) {
            if (!names.get(i).equals(reported.get(i))) {
                labelToIndex.put(names.get(i), i + 1);
            }
        }
        return labelToIndex;
    }

    /**
     * A driver that answers the way the specification has it: a column read by name is answered by the first column that
     * carries that name, and a column read by position is answered by the column at that position.
     */
    private ResultSet driver(List<String> reported, List<Object> values) {
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] { ResultSet.class }, (proxy, method, args) -> {
            String methodName = method.getName();
            if ("findColumn".equals(methodName) || (methodName.startsWith("get") && args != null && args.length > 0)) {
                boolean byPosition = int.class.equals(method.getParameterTypes()[0]);
                int column = byPosition ? (Integer) args[0] - 1 : reported.indexOf((String) args[0]);
                if (column < 0 || column >= values.size()) {
                    throw new SQLException("no such column: " + args[0]);
                }
                if ("findColumn".equals(methodName)) {
                    return column + 1;
                }
                return String.valueOf(values.get(column));
            }

            if (boolean.class.equals(method.getReturnType())) {
                return true;
            }
            return null;
        });
    }
}
