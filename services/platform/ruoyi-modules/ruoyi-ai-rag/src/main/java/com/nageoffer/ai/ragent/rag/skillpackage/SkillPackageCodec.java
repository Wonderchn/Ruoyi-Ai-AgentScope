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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * F11:4 技能包编解码（冻结契约：ZIP = {@code manifest.json + skill.md}）。
 *
 * <p><b>安全边界</b>（全部在写入数据库之前完成，失败零副作用）：
 * <ol>
 *   <li><b>大小</b>：ZIP ≤5 MiB；entry ≤8；单个解压 ≤2 MiB；解压总量 ≤10 MiB。
 *       声明值（中央目录）与实际解压值同时校验，防"声明小、实际大"的 ZIP bomb；</li>
 *   <li><b>路径</b>：拒绝绝对路径、反斜杠、{@code .}/{@code ..} 段、空段、控制字符；
 *       所有 entry 只读进内存，<b>绝不落盘</b>；</li>
 *   <li><b>类型</b>：读中央目录的 external attributes，拒绝 Unix 符号链接（mode 0xA000）
 *       与目录 entry；拒绝加密 entry 与非 deflate/stored 压缩方式；</li>
 *   <li><b>结构</b>：拒绝重复 entry（大小写不敏感）、ZIP64、中央目录越界；
 *       本地头与中央目录的 entry 名/数量必须一致；</li>
 *   <li><b>manifest</b>：严格 JSON（重复键拒绝）、字段白名单——包内出现
 *       {@code tenantId}/{@code createBy}/凭据等未知字段一律拒绝，不从包恢复身份；</li>
 *   <li><b>正文</b>：严格 UTF-8（坏编码拒绝），非空白，逐字保留（不 trim）。</li>
 * </ol>
 *
 * <p>额外 entry 允许存在但从不读取/执行；技能闭环只消费 {@code manifest.json} 与
 * {@code skill.md}。导出恒只写这两个 entry。
 */
public final class SkillPackageCodec {

    public static final int MAX_ZIP_BYTES = 5 * 1024 * 1024;
    public static final int MAX_ENTRIES = 8;
    public static final int MAX_ENTRY_BYTES = 2 * 1024 * 1024;
    public static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 10L * 1024 * 1024;

    static final String MANIFEST_ENTRY = "manifest.json";
    static final String CONTENT_ENTRY = "skill.md";

    private static final int EOCD_SIG = 0x06054b50;
    private static final int CENTRAL_SIG = 0x02014b50;
    private static final int LOCAL_SIG = 0x04034b50;

    private static final int FLAG_ENCRYPTED = 0x0001;
    private static final int FLAG_PATCHED_DATA = 0x0020;
    private static final int METHOD_STORED = 0;
    private static final int METHOD_DEFLATED = 8;

    private static final int UNIX_FILE_TYPE_MASK = 0xF000;
    private static final int UNIX_SYMLINK = 0xA000;
    private static final int UNIX_DIRECTORY = 0x4000;
    private static final int DOS_DIRECTORY = 0x10;

    private static final Pattern SKILL_CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");
    private static final int NAME_MAX_LENGTH = 64;
    private static final int DESCRIPTION_MAX_LENGTH = 512;
    private static final int TOOL_ID_MAX_LENGTH = 128;

    private static final Set<String> MANIFEST_FIELDS = Set.of(
            "schemaVersion", "skillCode", "name", "description",
            "toolIds", "sortOrder", "enabled", "contentFile");

    private SkillPackageCodec() {
    }

    // ------------------------------------------------------------------ 上传

    /**
     * 有界读取 multipart 文件；超过 ZIP 上限立即拒绝，不把整包读进堆。
     */
    public static byte[] readUpload(MultipartFile file) {
        if (file == null) {
            throw new SkillPackageException(SkillPackageError.MISSING_FILE);
        }
        if (file.getSize() > MAX_ZIP_BYTES) {
            throw new SkillPackageException(SkillPackageError.PACKAGE_TOO_LARGE, "ZIP 不能超过 5 MiB");
        }
        byte[] bytes;
        try (InputStream in = file.getInputStream()) {
            bytes = readAtMost(in, MAX_ZIP_BYTES, "ZIP 不能超过 5 MiB");
        } catch (IOException e) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "读取上传文件失败");
        }
        if (bytes.length == 0) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "技能包为空");
        }
        return bytes;
    }

    // ------------------------------------------------------------------ 解析

    /**
     * 解析并校验一个技能包；任何拒绝都在返回前抛出，调用方不会拿到半成品。
     */
    public static SkillPackageData read(byte[] zipBytes, ObjectMapper objectMapper) {
        Map<String, EntryMeta> directory = scanCentralDirectory(zipBytes);
        Map<String, byte[]> entries = extractEntries(zipBytes, directory);
        byte[] manifestBytes = entries.get(MANIFEST_ENTRY);
        byte[] contentBytes = entries.get(CONTENT_ENTRY);
        if (manifestBytes == null || contentBytes == null) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "缺少 manifest.json 或 skill.md");
        }
        String content = decodeUtf8(contentBytes);
        return parseManifest(manifestBytes, content, objectMapper);
    }

    private static Map<String, EntryMeta> scanCentralDirectory(byte[] zip) {
        if (zip.length < 22) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "不是有效的 ZIP 文件");
        }
        int eocd = findEocd(zip);
        int total = u16(zip, eocd + 10);
        long cdSize = u32(zip, eocd + 12);
        long cdOffset = u32(zip, eocd + 16);
        if (total == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "不支持 ZIP64");
        }
        if (total > MAX_ENTRIES) {
            throw new SkillPackageException(SkillPackageError.PACKAGE_TOO_LARGE,
                    "entry 数量不能超过 " + MAX_ENTRIES);
        }
        if (cdOffset < 0 || cdOffset + cdSize > eocd) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "中央目录越界");
        }

        Map<String, EntryMeta> directory = new LinkedHashMap<>();
        long totalUncompressed = 0;
        int p = (int) cdOffset;
        int end = (int) (cdOffset + cdSize);
        for (int i = 0; i < total; i++) {
            if (p + 46 > end || u32(zip, p) != CENTRAL_SIG) {
                throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "中央目录条目损坏");
            }
            int versionMadeBy = u16(zip, p + 4);
            int flags = u16(zip, p + 8);
            int method = u16(zip, p + 10);
            long compressedSize = u32(zip, p + 20);
            long uncompressedSize = u32(zip, p + 24);
            int nameLength = u16(zip, p + 28);
            int extraLength = u16(zip, p + 30);
            int commentLength = u16(zip, p + 32);
            long externalAttributes = u32(zip, p + 38);
            long localOffset = u32(zip, p + 42);
            int next = p + 46 + nameLength + extraLength + commentLength;
            if (next > end) {
                throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "中央目录条目越界");
            }
            String name = new String(zip, p + 46, nameLength, StandardCharsets.UTF_8);

            validateEntryName(name);
            if ((flags & (FLAG_ENCRYPTED | FLAG_PATCHED_DATA)) != 0) {
                throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "不支持加密 entry");
            }
            if (method != METHOD_STORED && method != METHOD_DEFLATED) {
                throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "不支持的压缩方式");
            }
            if (compressedSize == 0xFFFFFFFFL || uncompressedSize == 0xFFFFFFFFL
                    || localOffset == 0xFFFFFFFFL) {
                throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "不支持 ZIP64");
            }
            validateEntryType(name, versionMadeBy, externalAttributes);
            if (uncompressedSize > MAX_ENTRY_BYTES) {
                throw new SkillPackageException(SkillPackageError.PACKAGE_TOO_LARGE,
                        "单个 entry 解压不能超过 2 MiB");
            }
            totalUncompressed += uncompressedSize;
            if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                throw new SkillPackageException(SkillPackageError.PACKAGE_TOO_LARGE,
                        "解压总量不能超过 10 MiB");
            }
            String key = name.toLowerCase(Locale.ROOT);
            if (directory.putIfAbsent(key,
                    new EntryMeta(name, method, uncompressedSize, localOffset)) != null) {
                throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "重复 entry: " + name);
            }
            validateLocalHeader(zip, name, method, localOffset);
            p = next;
        }
        if (p != end) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "中央目录长度不一致");
        }
        if (!directory.containsKey(MANIFEST_ENTRY) || !directory.containsKey(CONTENT_ENTRY)) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "缺少 manifest.json 或 skill.md");
        }
        return directory;
    }

    private static void validateEntryType(String name, int versionMadeBy, long externalAttributes) {
        // Unix 把 st_mode 放在 external attributes 高 16 位；这里不依赖 versionMadeBy 的平台字节
        // ——打包器可以伪造平台标记，但 0xA000（符号链接）/0x4000（目录）出现在高 16 位就只有
        // 这两种含义，一律按 fail-closed 拒绝。
        int mode = (int) ((externalAttributes >>> 16) & 0xFFFF);
        int type = mode & UNIX_FILE_TYPE_MASK;
        if (type == UNIX_SYMLINK) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE,
                    "不允许符号链接 entry: " + name);
        }
        if (type == UNIX_DIRECTORY) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE,
                    "不允许目录 entry: " + name);
        }
        if ((externalAttributes & DOS_DIRECTORY) != 0 || name.endsWith("/")) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE,
                    "不允许目录 entry: " + name);
        }
    }

    private static void validateLocalHeader(byte[] zip, String centralName, int centralMethod,
                                            long localOffset) {
        if (localOffset < 0 || localOffset + 30 > zip.length
                || u32(zip, (int) localOffset) != LOCAL_SIG) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "本地头损坏: " + centralName);
        }
        int p = (int) localOffset;
        int flags = u16(zip, p + 6);
        int method = u16(zip, p + 8);
        int nameLength = u16(zip, p + 26);
        int extraLength = u16(zip, p + 28);
        if ((flags & (FLAG_ENCRYPTED | FLAG_PATCHED_DATA)) != 0) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "不支持加密 entry");
        }
        if (method != centralMethod) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE,
                    "本地头与中央目录压缩方式不一致: " + centralName);
        }
        long nameEnd = localOffset + 30 + nameLength + extraLength;
        if (nameEnd > zip.length) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "本地头越界: " + centralName);
        }
        String localName = new String(zip, p + 30, nameLength, StandardCharsets.UTF_8);
        if (!localName.equals(centralName)) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE,
                    "本地头与中央目录 entry 名不一致");
        }
    }

    private static void validateEntryName(String name) {
        if (name == null || name.isEmpty()) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "entry 名为空");
        }
        if (name.length() > 255) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "entry 名过长");
        }
        if (name.indexOf('\\') >= 0) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE,
                    "entry 名不得含反斜杠: " + name);
        }
        if (name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE,
                    "不允许绝对路径 entry: " + name);
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE,
                        "entry 名含控制字符");
            }
        }
        String[] segments = name.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE,
                        "不允许路径穿越 entry: " + name);
            }
        }
    }

    private static Map<String, byte[]> extractEntries(byte[] zip, Map<String, EntryMeta> directory) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long totalActual = 0;
        int counted = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                counted++;
                if (counted > MAX_ENTRIES) {
                    throw new SkillPackageException(SkillPackageError.PACKAGE_TOO_LARGE,
                            "entry 数量不能超过 " + MAX_ENTRIES);
                }
                String name = entry.getName();
                validateEntryName(name);
                String key = name.toLowerCase(Locale.ROOT);
                EntryMeta meta = directory.get(key);
                if (meta == null) {
                    throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE,
                            "entry 不在中央目录中: " + name);
                }
                if (entries.containsKey(key)) {
                    throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "重复 entry: " + name);
                }
                byte[] data = readEntry(input, name);
                totalActual += data.length;
                if (totalActual > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                    throw new SkillPackageException(SkillPackageError.PACKAGE_TOO_LARGE,
                            "解压总量不能超过 10 MiB");
                }
                if (data.length != meta.uncompressedSize()) {
                    throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE,
                            "entry 解压大小与声明不一致: " + name);
                }
                entries.put(key, data);
                input.closeEntry();
            }
        } catch (ZipException e) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "ZIP 结构损坏");
        } catch (IOException e) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "ZIP 读取失败");
        }
        if (counted != directory.size()) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "entry 数量与中央目录不一致");
        }
        return entries;
    }

    private static byte[] readEntry(InputStream input, String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (out.size() + read > MAX_ENTRY_BYTES) {
                throw new SkillPackageException(SkillPackageError.PACKAGE_TOO_LARGE,
                        "单个 entry 解压不能超过 2 MiB: " + name);
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ manifest

    private static SkillPackageData parseManifest(byte[] manifestBytes, String content,
                                                  ObjectMapper objectMapper) {
        JsonNode root;
        try (JsonParser parser = objectMapper.getFactory().createParser(stripUtf8Bom(manifestBytes))) {
            // 重复键会让"同一字段两个值"出现歧义；严格模式直接拒绝
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            root = objectMapper.readTree(parser);
        } catch (IOException e) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "manifest.json 不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "manifest.json 必须是对象");
        }
        Iterator<String> fields = root.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!MANIFEST_FIELDS.contains(field)) {
                throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE,
                        "manifest 含未知字段: " + field);
            }
        }

        JsonNode schemaVersion = root.get("schemaVersion");
        if (schemaVersion == null || !schemaVersion.isInt() || schemaVersion.intValue() != 1) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "schemaVersion 必须为 1");
        }
        String skillCode = requireText(root, "skillCode", 64);
        if (!SKILL_CODE_PATTERN.matcher(skillCode).matches()) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE,
                    "skillCode 只能用小写字母开头的字母、数字与下划线，长度 2~64");
        }
        String name = requireText(root, "name", NAME_MAX_LENGTH);
        String description = requireText(root, "description", DESCRIPTION_MAX_LENGTH);
        String contentFile = requireText(root, "contentFile", 64);
        if (!CONTENT_ENTRY.equals(contentFile)) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "contentFile 必须为 skill.md");
        }

        int sortOrder = 0;
        JsonNode sortOrderNode = root.get("sortOrder");
        if (sortOrderNode != null && !sortOrderNode.isNull()) {
            if (!sortOrderNode.isInt()) {
                throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "sortOrder 必须是整数");
            }
            sortOrder = sortOrderNode.intValue();
        }

        boolean enabled = true;
        JsonNode enabledNode = root.get("enabled");
        if (enabledNode != null && !enabledNode.isNull()) {
            if (!enabledNode.isBoolean()) {
                throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "enabled 必须是布尔值");
            }
            enabled = enabledNode.booleanValue();
        }

        List<String> toolIds = parseToolIds(root.get("toolIds"));
        if (content.isBlank()) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "skill.md 内容不能为空");
        }
        return new SkillPackageData(skillCode, name, description, toolIds, sortOrder, enabled, content);
    }

    private static List<String> parseToolIds(JsonNode toolIdsNode) {
        if (toolIdsNode == null || toolIdsNode.isNull()) {
            return List.of();
        }
        if (!toolIdsNode.isArray()) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "toolIds 必须是字符串数组");
        }
        LinkedHashSet<String> toolIds = new LinkedHashSet<>();
        for (JsonNode item : toolIdsNode) {
            if (!item.isTextual()) {
                throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "toolIds 必须是字符串数组");
            }
            String toolId = item.textValue().trim();
            if (toolId.isEmpty() || toolId.length() > TOOL_ID_MAX_LENGTH) {
                throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "toolIds 含非法值");
            }
            toolIds.add(toolId);
        }
        return List.copyOf(toolIds);
    }

    private static String requireText(JsonNode root, String field, int maxLength) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual()) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE,
                    "manifest 缺少字符串字段: " + field);
        }
        String value = node.textValue();
        if (value.trim().isEmpty()) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE,
                    "manifest 字段不能为空: " + field);
        }
        if (value.length() > maxLength) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE,
                    "manifest 字段超长: " + field);
        }
        return value;
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "skill.md 不是合法 UTF-8");
        }
    }

    private static byte[] stripUtf8Bom(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            byte[] stripped = new byte[bytes.length - 3];
            System.arraycopy(bytes, 3, stripped, 0, stripped.length);
            return stripped;
        }
        return bytes;
    }

    // ------------------------------------------------------------------ 写出

    /**
     * 写出契约 ZIP：恒为 manifest.json + skill.md 两个 entry，不写任何内部字段。
     */
    public static byte[] write(SkillPackageData data, ObjectMapper objectMapper) {
        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("schemaVersion", 1);
        manifest.put("skillCode", data.skillCode());
        manifest.put("name", data.name());
        manifest.put("description", data.description());
        ArrayNode toolIds = manifest.putArray("toolIds");
        data.toolIds().forEach(toolIds::add);
        manifest.put("sortOrder", data.sortOrder());
        manifest.put("enabled", data.enabled());
        manifest.put("contentFile", CONTENT_ENTRY);

        byte[] manifestBytes;
        try {
            manifestBytes = objectMapper.writeValueAsBytes(manifest);
        } catch (IOException e) {
            throw new IllegalStateException("生成技能包 manifest 失败", e);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
            zip.write(manifestBytes);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(CONTENT_ENTRY));
            zip.write(data.content().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException("生成技能包失败", e);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ 字节工具

    private static byte[] readAtMost(InputStream input, int max, String message) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (out.size() + read > max) {
                throw new SkillPackageException(SkillPackageError.PACKAGE_TOO_LARGE, message);
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static int findEocd(byte[] zip) {
        int lower = Math.max(0, zip.length - 22 - 0xFFFF);
        for (int i = zip.length - 22; i >= lower; i--) {
            if (u32(zip, i) == EOCD_SIG) {
                int commentLength = u16(zip, i + 20);
                if (i + 22 + commentLength == zip.length) {
                    return i;
                }
            }
        }
        throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "不是有效的 ZIP 文件");
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

    private record EntryMeta(String name, int method, long uncompressedSize, long localOffset) {
    }
}
