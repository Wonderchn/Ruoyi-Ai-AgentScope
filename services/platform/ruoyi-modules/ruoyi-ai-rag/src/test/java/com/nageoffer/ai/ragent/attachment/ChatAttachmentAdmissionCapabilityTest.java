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

package com.nageoffer.ai.ragent.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.authorization.TenantObjectReferenceRepository;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.ingest.PrivateObjectStore;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.config.ConfigAuthorityUnavailable;
import com.nageoffer.ai.ragent.runtime.config.EngineModelAuthority;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F04 brief7：文本模型零能力拒绝（rag 受理侧真实 {@code ChatAttachmentService.validateAdmission}）。
 *
 * <p><b>能力事实只有一处</b>：绑定 immutable revision 的保留键 {@code attachmentModalities}
 * （{@link AttachmentModalities} 单点解释）。本判据钉的是：默认拒图不是我"猜模型名"，
 * 而是受理时对着绑定版本参数读出的**缺省拒绝**——没有该键（或形态非法）时，图片引用
 * 必须在读字节之前被 BAD_REQUEST 挡住（零 {@code PrivateObjectStore} 交互 = 未读字节、
 * 零出站载荷），并且绝不能被降级成文本后继续受理。</p>
 *
 * <p><b>放行方向同场钉住</b>：显式声明 {@code ["image"]} 的版本对图片放行，且放行路径
 * 真的复验了字节（存在/长度/hash/策略）；文本文档不看该键（它只约束图片引用）。</p>
 */
@Tag("dev")
class ChatAttachmentAdmissionCapabilityTest {

    private static final String TENANT = "T1";
    private static final String MEMBER = "platform:T1:2101";
    private static final String USER = "2101";
    private static final String ATTACHMENT = "att-0123456789abcdef0123456789abcdef";
    private static final String OBJECT_KEY = TENANT + "/attachments/segment";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final byte[] PNG = png1x1();
    private static final byte[] TEXT = "note".getBytes(StandardCharsets.UTF_8);

    private final ExecutionPrincipal principal = new ExecutionPrincipal(TENANT, USER, MEMBER, 1, 1,
            Set.of("document.read"), "jti", "platform", 0, Long.MAX_VALUE);

    private JdbcTemplate jdbc;
    private PrivateObjectStore store;
    private ChatAttachmentService service;

    private static byte[] png1x1() {
        try {
            BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 给定附件 mime 与绑定 revision 的 params_json 文本，装配读端 mock。 */
    private void given(String mime, String capabilityJson) {
        byte[] bytes = mime.startsWith("image/") ? PNG : TEXT;
        String sha = mime.startsWith("image/") ? sha256(PNG) : sha256(TEXT);
        jdbc = mock(JdbcTemplate.class);
        store = mock(PrivateObjectStore.class);
        when(store.exists(anyString())).thenReturn(true);
        when(store.get(anyString())).thenReturn(bytes);

        when(jdbc.query(contains("FROM platform.ai_runtime_config_revision"), any(RowMapper.class),
                any(Object[].class))).thenReturn(List.of(capabilityJson));
        when(jdbc.query(contains("FROM platform.ai_chat_attachment a JOIN"), any(RowMapper.class),
                any(Object[].class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString(1)).thenReturn(mime.startsWith("image/") ? "pic.png" : "note.txt");
            when(rs.getString(2)).thenReturn(mime);
            when(rs.getLong(3)).thenReturn((long) bytes.length);
            when(rs.getString(4)).thenReturn(sha);
            when(rs.getString(5)).thenReturn(OBJECT_KEY);
            when(rs.getLong(6)).thenReturn(1L);
            return List.of(mapper.mapRow(rs, 0));
        });
        when(jdbc.queryForObject(contains("FROM platform.ai_conversation WHERE"), eq(Boolean.class),
                any(), any(), any(), any())).thenReturn(true);

        service = new ChatAttachmentService(jdbc, store, mock(TenantObjectReferenceRepository.class),
                mock(AiResourceAuthorizationService.class), mock(PlatformTransactionManager.class));
    }

    private static AdmissionRequest request() {
        try {
            return new AdmissionRequest(1, "rag.chat", "conv-1", null,
                    JSON.readTree("{\"text\":\"q\",\"attachments\":[{\"id\":\"" + ATTACHMENT
                            + "\",\"version\":1}]}"),
                    null, null, null, null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static EngineModelAuthority.PublishedModel model() {
        return new EngineModelAuthority.PublishedModel(TENANT, "rev-1", 1L, "deepseek", "deepseek-chat",
                "cat-v1", "params-1", "cred-ref-1", "op-1", Instant.EPOCH);
    }

    @Test
    @DisplayName("文本模型缺能力：图片受理 BAD_REQUEST，且在读字节之前拒绝（零 store 交互）")
    void textModelRefusesAnImageReferenceBeforeAnyByteIsRead() {
        given("image/png", "{\"temperature\":0.7}");
        RunApiException refused = assertThrows(RunApiException.class,
                () -> service.validateAdmission(principal, request(), model()));
        assertEquals(RunErrorCode.BAD_REQUEST, refused.errorCode());
        assertTrue(refused.getMessage().contains("does not support image attachments"),
                refused.getMessage());
        verify(store, never()).exists(anyString());
        verify(store, never()).get(anyString());
    }

    @Test
    @DisplayName("显式声明 [\"image\"] 的版本：图片放行且真的复验了字节")
    void imageCapableModelAdmitsAndVerifiesTheImageBytes() {
        given("image/png", "{\"attachmentModalities\":[\"image\"]}");
        assertDoesNotThrow(() -> service.validateAdmission(principal, request(), model()));
        verify(store).exists(OBJECT_KEY);
        verify(store).get(OBJECT_KEY);
    }

    @Test
    @DisplayName("文本文档不受图片能力键约束（无键也放行，且复验字节）")
    void textAttachmentsAreAdmittedRegardlessOfDeclaredModalities() {
        given("text/plain", "{\"temperature\":0.7}");
        assertDoesNotThrow(() -> service.validateAdmission(principal, request(), model()));
        verify(store).get(OBJECT_KEY);
    }

    @Test
    @DisplayName("非法形态的能力声明（字符串而非数组）：仍然拒图，绝不宽松放行")
    void aMalformedCapabilityDeclarationStillRefusesImages() {
        given("image/png", "{\"attachmentModalities\":\"image\"}");
        RunApiException refused = assertThrows(RunApiException.class,
                () -> service.validateAdmission(principal, request(), model()));
        assertEquals(RunErrorCode.BAD_REQUEST, refused.errorCode());
        assertTrue(refused.getMessage().contains("does not support image attachments"),
                refused.getMessage());
        verify(store, never()).exists(anyString());
        verify(store, never()).get(anyString());
    }

    @Test
    @DisplayName("读不到绑定版本参数：响亮拒绝受理，且在读字节之前（不猜能力）")
    void unreadableRevisionRefusesAdmissionBeforeAnyByteIsRead() {
        given("image/png", "{}");
        when(jdbc.query(contains("FROM platform.ai_runtime_config_revision"), any(RowMapper.class),
                any(Object[].class))).thenReturn(List.of());
        assertThrows(ConfigAuthorityUnavailable.class,
                () -> service.validateAdmission(principal, request(), model()));
        verify(store, never()).exists(anyString());
        verify(store, never()).get(anyString());
    }
}
