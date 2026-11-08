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

import java.sql.SQLException;

import org.junit.Assert;
import org.junit.Test;

/**
 * The page distinguishes a dialect not being able to describe a type from the read having failed, so the distinction has
 * to hold for the way a dialect plugin reports it and for the way it arrives once it travelled to the datasource worker
 * and back, where only the text is guaranteed to survive.
 */
public class DmObjectDefinitionUtilsTest {

    @Test
    public void unsupportedTypeIsRecognisedWhereverItSitsInTheChain() {
        Assert.assertTrue(DmObjectDefinitionUtils.isUnsupportedType(new UnsupportedOperationException("Oracle 'Sequence' Unsupported.")));

        // how the dialect plugins wrap it before handing it to the console
        Exception wrapped = new RuntimeException("requestObjectScript error.msg:UnsupportedOperationException: Oracle 'Sequence' Unsupported.",
            new UnsupportedOperationException("Oracle 'Sequence' Unsupported."));
        Assert.assertTrue(DmObjectDefinitionUtils.isUnsupportedType(wrapped));
    }

    @Test
    public void unsupportedTypeIsRecognisedFromTextAloneAfterTheRemoteHop() {
        RuntimeException remote = new RuntimeException("java.lang.RuntimeException: requestObjectScript error.msg:UnsupportedOperationException: DB2 'Synonym' Unsupported.");
        Assert.assertTrue(DmObjectDefinitionUtils.isUnsupportedType(remote));
    }

    @Test
    public void aFailingReadStaysAFailure() {
        Assert.assertFalse(DmObjectDefinitionUtils.isUnsupportedType(new RuntimeException("query failed", new SQLException("ORA-00942: table or view does not exist"))));
        Assert.assertFalse(DmObjectDefinitionUtils.isUnsupportedType(null));
    }
}
