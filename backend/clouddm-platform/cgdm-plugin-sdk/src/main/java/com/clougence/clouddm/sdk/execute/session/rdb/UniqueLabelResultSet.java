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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A view of a result set whose columns are read through the names that were given to the result.
 * <p>
 * The values of a result are fetched by column name, and a driver answers a name with the first column that carries it.
 * That is exactly what must not happen once a repeated name has been numbered, because then the second column of the
 * statement would be read as its first one. Every name of the result therefore comes with the position it was read
 * from, and this view answers a name of the result with that position, which is the only way to reach the value a
 * driver reports under a name that the statement used more than once.
 * <p>
 * A name that is not one of the result is left to the driver, so the case insensitive lookups of the drivers keep
 * working as they did, and a result without any repeated name is returned untouched.
 */
public final class UniqueLabelResultSet {

    private static final String                        FIND_COLUMN     = "findColumn";
    /** Position based overload of a name based accessor, by the name based accessor. */
    private static final Map<Method, Optional<Method>> INDEX_OVERLOADS = new ConcurrentHashMap<>();

    private UniqueLabelResultSet(){
    }

    /**
     * @param rs           result set of the statement
     * @param labelToIndex name of every column of the result to its position inside the statement
     * @return a view of the result set, or the result set itself when no name has to be translated
     */
    public static ResultSet wrap(ResultSet rs, Map<String, Integer> labelToIndex) {
        if (rs == null || labelToIndex == null || labelToIndex.isEmpty()) {
            return rs;
        }

        ClassLoader loader = ResultSet.class.getClassLoader();
        return (ResultSet) Proxy.newProxyInstance(loader, new Class<?>[] { ResultSet.class }, new Handler(rs, labelToIndex));
    }

    private static boolean isColumnAccessor(String methodName) {
        return FIND_COLUMN.equals(methodName) || methodName.startsWith("get") || methodName.startsWith("update");
    }

    /**
     * The overload of the accessor that takes the position of the column, which the JDBC specification defines for every
     * accessor that takes its name.
     */
    private static Optional<Method> indexOverload(Method accessor) {
        return INDEX_OVERLOADS.computeIfAbsent(accessor, method -> {
            Class<?>[] parameterTypes = method.getParameterTypes().clone();
            parameterTypes[0] = int.class;
            try {
                return Optional.of(ResultSet.class.getMethod(method.getName(), parameterTypes));
            } catch (NoSuchMethodException e) {
                return Optional.empty();
            }
        });
    }

    private static final class Handler implements InvocationHandler {

        private final ResultSet            delegate;
        private final Map<String, Integer> labelToIndex;

        private Handler(ResultSet delegate, Map<String, Integer> labelToIndex){
            this.delegate = delegate;
            this.labelToIndex = labelToIndex;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (args == null || args.length == 0 || !(args[0] instanceof String) || !isColumnAccessor(method.getName())) {
                return this.invokeDelegate(method, args);
            }

            Integer column = this.labelToIndex.get((String) args[0]);
            if (column == null) {
                return this.invokeDelegate(method, args);
            }

            if (FIND_COLUMN.equals(method.getName())) {
                return column;
            }

            Optional<Method> byIndex = indexOverload(method);
            if (byIndex.isEmpty()) {
                return this.invokeDelegate(method, args);
            }

            Object[] arguments = new Object[args.length];
            arguments[0] = column;
            System.arraycopy(args, 1, arguments, 1, args.length - 1);
            return this.invokeDelegate(byIndex.get(), arguments);
        }

        private Object invokeDelegate(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(this.delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
