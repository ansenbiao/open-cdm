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
package com.clougence.clouddm.console.web.util;

import java.util.Arrays;
import java.util.List;

import com.clougence.utils.ExceptionUtils;

/**
 * Tells apart a dialect not being able to describe an object type from a real failure of the read.
 */
public final class DmObjectDefinitionUtils {

    /**
     * Dialect plugins report an undecodable object type by throwing UnsupportedOperationException with a message
     * ending in "Unsupported.". The definition of an object is read inside the datasource worker, so by the time the
     * problem reaches this process it is usually only the text of a remote failure and the exception type is gone;
     * therefore both forms are accepted.
     */
    private static final List<String> UNSUPPORTED_MARKERS = Arrays.asList("UnsupportedOperationException", "Unsupported.");

    private DmObjectDefinitionUtils(){
    }

    public static boolean isUnsupportedType(Throwable error) {
        if (error == null) {
            return false;
        }
        for (Throwable throwable : ExceptionUtils.getThrowables(error)) {
            if (throwable instanceof UnsupportedOperationException) {
                return true;
            }
            String message = throwable.getMessage();
            if (message != null && UNSUPPORTED_MARKERS.stream().anyMatch(message::contains)) {
                return true;
            }
        }
        return false;
    }
}
