/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.knowledge.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkBudget;
import com.nageoffer.ai.ragent.core.ingest.IngestionSpec;
import com.nageoffer.ai.ragent.core.parser.registry.ParseProfile;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R17 F05:6：摄取规格<b>提交路径</b>（{@link IngestionSpecCodec#normalize(String)}）的严格校验，
 * 与既有 {@code IngestionSpecCodecTest}（关注"整篇不分块"往返与越界上限）互补。
 *
 * <p>判据映射：
 * <ul>
 *   <li>判据 1（非法 spec 拒绝、不静默 fallback）：{@link #unknownFieldIsRejectedInsteadOfSilentlyIgnored}、
 *       {@link #wrongTypedValueIsRejectedInsteadOfSilentlyDefaulted}、
 *       {@link #outOfRangeValueIsRejectedInsteadOfSilentlyDefaulted}、
 *       {@link #unknownParseProfileIsRejected}</li>
 *   <li>判据 2（配置实际被采用、随 spec 变化）：{@link #validSpecIsNormalizedIntoAdoptedValues}、
 *       {@link #twoSpecsNormalizeToDifferentAdoptedValues}</li>
 *   <li>判据 3（缺省/边界语义钉死）：{@link #absentSpecKeepsItsExistingDefaultSemantics}、
 *       {@link #wholeDocumentSentinelExemptsSemanticallyIgnoredBudgetFields}、
 *       {@link #storedReadStaysLenientForLegacyOrCorruptRows}</li>
 * </ul>
 *
 * <p>"静默 fallback"指：字段写错（类型/键名/取值）却被当成缺省值收下，前端如实提交、后端如实入库、
 * 没有任何一步报错，用户以为配置生效了。本类把每条静默路径钉成显式拒绝。
 */
@Tag("dev")
class IngestionSpecCodecStrictnessTest {

    private final IngestionSpecCodec codec = new IngestionSpecCodec(new ObjectMapper());

    /** 未知键名：schema 只下发 5 个键，多一个都说明前端与后端对不上，必须拒绝而不是丢掉。 */
    @Test
    void unknownFieldIsRejectedInsteadOfSilentlyIgnored() {
        ClientException ex = assertThrows(ClientException.class,
                () -> codec.normalize("{\"maxChars\":600,\"chunkSize\":120}"));

        assertTrue(ex.getErrorMessage().contains("chunkSize"),
                "报错必须点名未知字段，便于定位前后端契约漂移：" + ex.getErrorMessage());
    }

    /** 类型错：字符串数字可以宽松接受，但布尔/数组/小数这类"根本不是这个字段"的必须拒绝。 */
    @Test
    void wrongTypedValueIsRejectedInsteadOfSilentlyDefaulted() {
        assertThrows(ClientException.class, () -> codec.normalize("{\"maxChars\":\"abc\"}"));
        assertThrows(ClientException.class, () -> codec.normalize("{\"rowsPerChunk\":true}"));
        assertThrows(ClientException.class, () -> codec.normalize("{\"overlapChars\":[64]}"));
        assertThrows(ClientException.class, () -> codec.normalize("{\"maxChars\":600.5}"));
    }

    /**
     * 越界取值：负重叠 / 零行数 / 零容忍倍数 / 非哨兵负块大小，此前都被悄悄换成默认值。
     * 默认值不是"最接近的合法值"，而是另一套语义——静默替换等于改了用户的配置。
     */
    @Test
    void outOfRangeValueIsRejectedInsteadOfSilentlyDefaulted() {
        assertThrows(ClientException.class, () -> codec.normalize("{\"overlapChars\":-5}"));
        assertThrows(ClientException.class, () -> codec.normalize("{\"rowsPerChunk\":0}"));
        assertThrows(ClientException.class, () -> codec.normalize("{\"toleranceFactor\":0}"));
        assertThrows(ClientException.class, () -> codec.normalize("{\"maxChars\":-2}"));
    }

    /** 未认领的解析档位本就该拒（{@code ParseProfile.from} 明确拒绝而非兜底），此处作为回归钉住。 */
    @Test
    void unknownParseProfileIsRejected() {
        assertThrows(ClientException.class, () -> codec.normalize("{\"parseProfile\":\"deep-fidelity\"}"));
    }

    /** 合法 spec 的采用值必须逐格进落库 JSON（不是"能存"而是"存的就是提交的"）。 */
    @Test
    void validSpecIsNormalizedIntoAdoptedValues() {
        String stored = codec.normalize("{\"parseProfile\":\"fidelity\",\"maxChars\":512,"
                + "\"overlapChars\":64,\"rowsPerChunk\":30,\"toleranceFactor\":2}");

        IngestionSpec spec = codec.read(stored);
        assertEquals(ParseProfile.FIDELITY, spec.parseProfile());
        assertEquals(512, spec.budget().maxChars());
        assertEquals(64, spec.budget().overlapChars());
        assertEquals(30, spec.budget().rowsPerChunk());
        assertEquals(2, spec.budget().toleranceFactor());
    }

    /** 两组配置归一化出的落库值必须不同——相同就说明配置压根没被消费。 */
    @Test
    void twoSpecsNormalizeToDifferentAdoptedValues() {
        String fastSmall = codec.normalize("{\"parseProfile\":\"fast\",\"maxChars\":512,\"overlapChars\":64}");
        String fidelityLarge = codec.normalize("{\"parseProfile\":\"fidelity\",\"maxChars\":2048,"
                + "\"overlapChars\":256,\"rowsPerChunk\":20}");

        assertNotEquals(fastSmall, fidelityLarge);
        IngestionSpec small = codec.read(fastSmall);
        IngestionSpec large = codec.read(fidelityLarge);
        assertEquals(ParseProfile.FAST, small.parseProfile());
        assertEquals(ParseProfile.FIDELITY, large.parseProfile());
        assertEquals(512, small.budget().maxChars());
        assertEquals(2048, large.budget().maxChars());
        assertNotEquals(codec.write(IngestionSpec.defaults()), fastSmall,
                "落库值不能等于全默认值，否则无法证明提交值被采用");
    }

    /** 缺省主路径：入参为空返回 null（列留空 = 走默认），"{}" 才落一份全默认 JSON。 */
    @Test
    void absentSpecKeepsItsExistingDefaultSemantics() {
        assertNull(codec.normalize(null));
        assertNull(codec.normalize(""));
        assertNull(codec.normalize("   "));

        IngestionSpec defaults = codec.read(codec.normalize("{}"));
        assertEquals(ParseProfile.defaultProfile(), defaults.parseProfile());
        assertEquals(ChunkBudget.defaults().maxChars(), defaults.budget().maxChars());
        assertEquals(ChunkBudget.defaults().overlapChars(), defaults.budget().overlapChars());
        assertEquals(ChunkBudget.defaults().rowsPerChunk(), defaults.budget().rowsPerChunk());
        assertEquals(ChunkBudget.defaults().toleranceFactor(), defaults.budget().toleranceFactor());
    }

    /**
     * 整篇不分块（哨兵 -1）下 overlap/rows 按定义不参与分块，线路上本就允许是 0/-1
     * （见 codec 写入形状），不能因为"看着像越界"就把合法的整篇不分块拒掉——避免矫枉过正。
     */
    @Test
    void wholeDocumentSentinelExemptsSemanticallyIgnoredBudgetFields() {
        String stored = codec.normalize("{\"maxChars\":-1,\"overlapChars\":0,\"rowsPerChunk\":-1}");

        assertTrue(codec.read(stored).budget().isWholeDocument());
        assertTrue(stored.contains("\"maxChars\":-1"), stored);
    }

    /**
     * 读路径（{@link IngestionSpecCodec#read(String)}）对存量行保持宽松：未知字段/损坏 JSON 一律回落默认且不抛。
     * 提交路径收紧不得把历史行读成异常——这是本类改动最容易被误伤的边界。
     */
    @Test
    void storedReadStaysLenientForLegacyOrCorruptRows() {
        assertEquals(IngestionSpec.defaults().budget().maxChars(),
                codec.read("{\"legacyExtra\":1,\"maxChars\":700}").budget().maxChars());
        assertEquals(IngestionSpec.defaults().parseProfile(), codec.read("not-json").parseProfile());
        assertEquals(IngestionSpec.defaults().budget().maxChars(), codec.read(null).budget().maxChars());
    }
}
