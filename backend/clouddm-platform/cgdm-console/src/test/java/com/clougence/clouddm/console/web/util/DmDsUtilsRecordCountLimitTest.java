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

import org.junit.Assert;
import org.junit.Test;

/** 行数钳制契约：系统配置是治理红线，客户端取值只能在其之下收窄结果集。 */
public class DmDsUtilsRecordCountLimitTest {

    @Test
    public void requestedValueMustNotExceedSystemCeiling() {
        Assert.assertEquals(1000, DmDsUtils.resolveFetchRecordCountLimit(5000, 1000));
        Assert.assertEquals(500, DmDsUtils.resolveFetchRecordCountLimit(500, 1000));
    }

    @Test
    public void unspecifiedAndUnlimitedRequestBothFallBackToSystemCeiling() {
        // 0 表示未指定，-1 表示客户端请求不限制：系统上限为正数时都不能突破红线
        Assert.assertEquals(1000, DmDsUtils.resolveFetchRecordCountLimit(0, 1000));
        Assert.assertEquals(1000, DmDsUtils.resolveFetchRecordCountLimit(-1, 1000));
    }

    @Test
    public void missingOrIllegalSystemConfigFallsBackToDefaultLimit() {
        // 配置缺失/非法时不能退化为“不限制”
        Assert.assertEquals(DmDsUtils.DEFAULT_RECORD_COUNT_LIMIT, DmDsUtils.resolveFetchRecordCountLimit(0, 0));
        Assert.assertEquals(DmDsUtils.DEFAULT_RECORD_COUNT_LIMIT, DmDsUtils.resolveFetchRecordCountLimit(-1, 0));
        Assert.assertEquals(500, DmDsUtils.resolveFetchRecordCountLimit(500, 0));
    }

    @Test
    public void unlimitedSystemConfigKeepsUnlimitedAndExplicitValues() {
        // 管理员放开红线（系统上限为负数）时：未指定按不限制处理，显式取值仍然生效
        Assert.assertEquals(-1, DmDsUtils.resolveFetchRecordCountLimit(0, -1));
        Assert.assertEquals(-1, DmDsUtils.resolveFetchRecordCountLimit(-1, -1));
        Assert.assertEquals(200, DmDsUtils.resolveFetchRecordCountLimit(200, -1));
    }
}
