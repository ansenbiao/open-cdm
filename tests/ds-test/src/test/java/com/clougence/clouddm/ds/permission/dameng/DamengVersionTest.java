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
package com.clougence.clouddm.ds.permission.dameng;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.clougence.clouddm.ds.dameng.sql.parser.DmVersion;

/**
 * 达梦版本口径契约：免鉴权系统资源规则只在 DM8 生效，版本误判会直接放大或收紧鉴权范围。
 *
 * <p>数据源自述版本形如 “DM Database Server 64 V8”，首个数字串是服务器位数（64/32）而不是大版本。</p>
 */
public final class DamengVersionTest {

    @Test
    public void plainVersionNumbersResolveToOwnMajor() {
        Assertions.assertEquals(DmVersion.DM_8, DmVersion.parse("8"));
        Assertions.assertEquals(DmVersion.DM_7, DmVersion.parse("7"));
        Assertions.assertEquals(DmVersion.DM_6, DmVersion.parse("6"));
        Assertions.assertTrue(DmVersion.isRecognized("8.1.3.142"));
    }

    @Test
    public void serverVersionMarkerWinsOverWordSizeDigits() {
        Assertions.assertEquals(DmVersion.DM_8, DmVersion.parse("DM Database Server 64 V8"));
        Assertions.assertEquals(DmVersion.DM_7, DmVersion.parse("DM Database Server 32 V7"));
        Assertions.assertTrue(DmVersion.isRecognized("DM Database Server 64 V8"));
        Assertions.assertTrue(DmVersion.isRecognized("DM Database Server 32 V7"));
    }

    @Test
    public void unrecognizedVersionFallsBackToLatestButStaysFlagged() {
        Assertions.assertEquals(DmVersion.LATEST, DmVersion.parse(null));
        Assertions.assertEquals(DmVersion.LATEST, DmVersion.parse("  "));
        Assertions.assertEquals(DmVersion.LATEST, DmVersion.parse("DM Database Server Express"));
        Assertions.assertFalse(DmVersion.isRecognized(null));
        Assertions.assertFalse(DmVersion.isRecognized("DM Database Server Express"));
        Assertions.assertFalse(DmVersion.isRecognized("DM Database Server 64 V9"));
    }

    @Test
    public void exemptionGateRequiresDm8() {
        Assertions.assertFalse(DmVersion.ge(DmVersion.parse("DM Database Server 32 V7"), DmVersion.DM_8));
        Assertions.assertTrue(DmVersion.ge(DmVersion.parse("DM Database Server 64 V8"), DmVersion.DM_8));
    }
}
