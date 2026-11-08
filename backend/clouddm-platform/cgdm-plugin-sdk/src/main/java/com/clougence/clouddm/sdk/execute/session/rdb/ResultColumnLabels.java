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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Names of the columns of a result, with a name of its own for every column.
 * <p>
 * Everything that is built on top of a result keeps its columns under their name: the metadata of the session, the cache
 * file of the result set, the header of the grid, and the rules of the masking plugin. A statement is free to report the
 * same name twice, and a data source does that by itself for {@code select t.*, t.* from t}, so the names that come out
 * of the driver cannot be used as they are. Rather than let the repeated columns collapse into one, the later ones get a
 * name of their own.
 */
public final class ResultColumnLabels {

    /** Separator of a generated name. A name built with it is still passed over when the statement reports it. */
    private static final String SEPARATOR = "_";

    private ResultColumnLabels(){
    }

    /**
     * @param reported the column names as the data source reported them, in the order of the statement
     * @return the name of every column, in the order of the statement; the names are the reported ones whenever none is
     *         repeated
     */
    public static List<String> unique(List<String> reported) {
        if (reported == null || reported.isEmpty()) {
            return reported == null ? Collections.emptyList() : new ArrayList<>(reported);
        }

        Set<String> reportedNames = new HashSet<>(reported);
        if (reportedNames.size() == reported.size()) {
            return new ArrayList<>(reported);
        }

        List<String> names = new ArrayList<>(reported.size());
        Set<String> used = new HashSet<>();
        Map<String, Integer> lastNumber = new HashMap<>();
        for (String name : reported) {
            String unique = name;
            if (name != null && used.contains(name)) {
                // Only the second and later columns that share a name are numbered, so the first one keeps the name the
                // statement gave it. A number that the statement reports itself, or that was given out already, is
                // passed over, so that a generated name can never take the place of a reported one.
                int number = lastNumber.getOrDefault(name, 1);
                do {
                    number++;
                    unique = name + SEPARATOR + number;
                } while (reportedNames.contains(unique) || used.contains(unique));
                lastNumber.put(name, number);
            }

            names.add(unique);
            used.add(unique);
        }
        return names;
    }

    /**
     * Names which cannot address a single column because they were reported for more than one of them. A caller
     * holding information which belongs to one specific column, such as the tables that column was computed from,
     * has no position left to attach it to once repeated names are renamed, and attaching it to the wrong column
     * would be worse than not attaching it at all.
     *
     * @param reported names as the data source or the parser reported them, in statement order
     * @return the names which occur more than once
     */
    public static Set<String> duplicated(List<String> reported) {
        if (reported == null || reported.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> duplicated = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (String name : reported) {
            if (name != null && !seen.add(name)) {
                duplicated.add(name);
            }
        }
        return duplicated;
    }
}
