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

package com.nageoffer.ai.ragent.rag.skillpackage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * F11:4 技能包测试夹具。
 *
 * <p><b>为什么不复用生产 codec 造恶意包</b>：如果测试只用生产写出逻辑造包，packager 与
 * parser 同错时会对称通过。本夹具用 JDK 的 {@link ZipOutputStream} 独立造包，并直接改写
 * 中央目录字段来覆盖生产解析器必须独立识别的形状（Unix 符号链接外部属性、重复 entry）。
 */
final class SkillPackageTestFixtures {

    static final ObjectMapper MAPPER = new ObjectMapper();

    static final String SKILL_MD = "skill.md";
    static final String MANIFEST_JSON = "manifest.json";

    private static final int EOCD_SIG = 0x06054b50;
    private static final int CEN_SIG = 0x02014b50;

    private SkillPackageTestFixtures() {
    }

    /** 契约字段完整、enabled/sortOrder 可缺省的 manifest。 */
    static byte[] manifest(String skillCode, String name, String description,
                           List<String> toolIds, Integer sortOrder, Boolean enabled) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("skillCode", skillCode);
        root.put("name", name);
        root.put("description", description);
        var ids = root.putArray("toolIds");
        toolIds.forEach(ids::add);
        if (sortOrder != null) {
            root.put("sortOrder", sortOrder);
        }
        if (enabled != null) {
            root.put("enabled", enabled);
        }
        root.put("contentFile", SKILL_MD);
        return bytes(root);
    }

    static byte[] validPackage(String skillCode, String name, String description,
                               List<String> toolIds, Integer sortOrder, Boolean enabled, String content) {
        return zipWithRawManifest(manifest(skillCode, name, description, toolIds, sortOrder, enabled), content);
    }

    static byte[] zipWithRawManifest(byte[] manifestBytes, String content) {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(MANIFEST_JSON, manifestBytes);
        entries.put(SKILL_MD, content.getBytes(StandardCharsets.UTF_8));
        return zip(entries);
    }

    static byte[] zipWithRawManifestAndExtras(byte[] manifestBytes, String content, Map<String, byte[]> extras) {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(MANIFEST_JSON, manifestBytes);
        entries.put(SKILL_MD, content.getBytes(StandardCharsets.UTF_8));
        entries.putAll(extras);
        return zip(entries);
    }

    static byte[] zipWithEntries(Map<String, byte[]> entries) {
        return zip(entries);
    }

    static byte[] zip(Map<String, byte[]> entries) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("构造测试 ZIP 失败", e);
        }
    }

    static Map<String, byte[]> unzip(byte[] zipBytes) {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取测试 ZIP 失败", e);
        }
        return entries;
    }

    static String manifestJson(byte[] zipBytes) {
        return new String(unzip(zipBytes).get(MANIFEST_JSON), StandardCharsets.UTF_8);
    }

    /**
     * 只改中央目录的 external attributes（Unix mode 在高 16 位）。ZIP 规范里符号链接是
     * {@code 0xA000} 家族；不构造这种包就测不到"符号链接必须拒绝"。
     */
    static byte[] patchExternalAttributes(byte[] original, String entryName, long attributes) {
        byte[] zip = original.clone();
        int eocd = findEocd(zip);
        int total = u16(zip, eocd + 10);
        int p = (int) u32(zip, eocd + 16);
        for (int i = 0; i < total; i++) {
            requireCentralHeader(zip, p);
            int nameLen = u16(zip, p + 28);
            int extraLen = u16(zip, p + 30);
            int commentLen = u16(zip, p + 32);
            String name = new String(zip, p + 46, nameLen, StandardCharsets.UTF_8);
            if (name.equals(entryName)) {
                putU32(zip, p + 38, attributes);
                return zip;
            }
            p += 46 + nameLen + extraLen + commentLen;
        }
        throw new IllegalStateException("测试包装里找不到 entry: " + entryName);
    }

    /**
     * 把等长 entry 名改成另一个名字（本地头与中央目录同步改），用于构造"重复 entry"。
     */
    static byte[] patchEntryName(byte[] original, String from, String to) {
        if (from.length() != to.length()) {
            throw new IllegalArgumentException("测试夹具只做等长改名");
        }
        byte[] zip = original.clone();
        byte[] replacement = to.getBytes(StandardCharsets.UTF_8);
        int eocd = findEocd(zip);
        int total = u16(zip, eocd + 10);
        int p = (int) u32(zip, eocd + 16);
        for (int i = 0; i < total; i++) {
            requireCentralHeader(zip, p);
            int nameLen = u16(zip, p + 28);
            int extraLen = u16(zip, p + 30);
            int commentLen = u16(zip, p + 32);
            String name = new String(zip, p + 46, nameLen, StandardCharsets.UTF_8);
            if (name.equals(from)) {
                int localOffset = (int) u32(zip, p + 42);
                System.arraycopy(replacement, 0, zip, localOffset + 30, replacement.length);
                System.arraycopy(replacement, 0, zip, p + 46, replacement.length);
                return zip;
            }
            p += 46 + nameLen + extraLen + commentLen;
        }
        throw new IllegalStateException("测试包装里找不到 entry: " + from);
    }

    private static byte[] bytes(ObjectNode node) {
        try {
            return MAPPER.writeValueAsBytes(node);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void requireCentralHeader(byte[] zip, int p) {
        if (p + 46 > zip.length || u32(zip, p) != CEN_SIG) {
            throw new IllegalStateException("测试包装中央目录损坏 @" + p);
        }
    }

    private static int findEocd(byte[] zip) {
        int lower = Math.max(0, zip.length - 22 - 0xFFFF);
        for (int i = zip.length - 22; i >= lower; i--) {
            if (u32(zip, i) == EOCD_SIG) {
                int commentLen = u16(zip, i + 20);
                if (i + 22 + commentLen == zip.length) {
                    return i;
                }
            }
        }
        throw new IllegalStateException("测试包装缺 EOCD");
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFFL)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 3] & 0xFFL) << 24);
    }

    private static void putU32(byte[] bytes, int offset, long value) {
        bytes[offset] = (byte) (value & 0xFF);
        bytes[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        bytes[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        bytes[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }
}
