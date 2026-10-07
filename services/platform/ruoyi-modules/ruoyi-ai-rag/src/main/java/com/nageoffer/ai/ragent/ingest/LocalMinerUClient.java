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

package com.nageoffer.ai.ragent.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 本地 MinerU V1 API 适配器（已按真实服务实测冻结：uploads → content → complete →
 * parse/jobs → 轮询 → files/{id}/content）。
 *
 * <p>与云 V4 客户端（{@code rag.core.parser.mineru.MinerUClient}）完全独立；
 * 产品浏览器不直连 MinerU，内部 jobId 不作为资源授权。任务索引在服务进程内，
 * 重启后旧 jobId 返回 404 → 明确分类为 {@link JobLostException}，由上层决定
 * 复用既有产物或持久新增解析 attempt 重提，绝不默认完成。
 */
@Component
@ConditionalOnProperty(name = "mineru.local.enabled", havingValue = "true")
public class LocalMinerUClient implements org.ruoyi.ai.api.runtime.MinerUPort {

    private static final Logger log = LoggerFactory.getLogger(LocalMinerUClient.class);

    private final LocalMinerUProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public LocalMinerUClient(LocalMinerUProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()
                || properties.getToken() == null || properties.getToken().isBlank()) {
            throw new IllegalStateException("mineru.local.base-url and token are required when enabled");
        }
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 服务不可用/协议异常（503，不静默重试到无界）。 */


    /** 任务索引丢失（重启后旧 jobId 404）：必须分类处理，不得视为完成。 */




    /** 提交解析并等待完成；超过 deadline 抛 DEPENDENCY_UNAVAILABLE（由上层分类）。 */
    public ParseResult parse(byte[] pdfBytes, String filename, String sha256, int deadlineSeconds) {
        return awaitResult(submit(pdfBytes,filename,sha256),deadlineSeconds);
    }



    public ParseJob submit(byte[] pdfBytes,String filename,String sha256) {
        if (pdfBytes.length > properties.getMaxBytes()) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "file exceeds mineru.local.max-bytes");
        }
        JsonNode upload = createUpload(filename, pdfBytes.length, sha256);
        String fileId;
        if("completed".equals(upload.path("status").asText())) {
            fileId=upload.path("file").path("id").asText("");
            if(fileId.isBlank()) throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE,"cached mineru file missing");
        } else if("pending".equals(upload.path("status").asText())) {
            String uploadId=upload.path("id").asText();
            putContent(uploadId, pdfBytes);
            fileId = completeUpload(uploadId, sha256);
        } else throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE,"mineru upload status invalid");
        String jobId = createJob(fileId);
        return new ParseJob(jobId,fileId);
    }

    public ParseResult awaitResult(ParseJob submitted,int deadlineSeconds) {
        return awaitResult(submitted,deadlineSeconds,()->false);
    }

    public ParseResult awaitResult(ParseJob submitted,int deadlineSeconds,java.util.function.BooleanSupplier cancelled) {
        String jobId=submitted.jobId(),fileId=submitted.fileId();
        JsonNode job = awaitJob(jobId, deadlineSeconds,cancelled);
        JsonNode file = job.path("files").path(0);
        String status = file.path("status").asText("");
        if (!"completed".equals(status)) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE,
                    "mineru parse did not complete: " + status);
        }
        String markdownFileId = file.path("output_files").path("markdown").path("file_id").asText("");
        if (markdownFileId.isBlank()) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE, "mineru produced no markdown output");
        }
        byte[] markdown = downloadFile(markdownFileId);
        String structuredFileId = file.path("output_files").path("structured_content").path("file_id").asText("");
        if (structuredFileId.isBlank()) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE, "mineru produced no structured page output");
        }
        byte[] structured = downloadFile(structuredFileId);
        String structuredContent = new String(structured, StandardCharsets.UTF_8);
        MinerUPageContent.decode(structuredContent, objectMapper);
        String markdownSha = sha256Hex(markdown);
        return new ParseResult(new String(markdown, StandardCharsets.UTF_8), markdownSha, jobId, fileId,
                job.path("tier").asText(properties.getTier()),
                file.path("parse").path("parser_version").asText(null),
                file.path("parse").path("duration_ms").isNumber() ? file.path("parse").path("duration_ms").asLong() : null,
                file.path("page_range").asText(""), structuredContent, sha256Hex(structured));
    }

    /** 取消任务（尽力而为；失败不掩盖主流程状态）。 */
    public void cancel(String jobId) {
        try {
            HttpRequest request = request("/v1/parse/jobs/" + jobId)
                    .DELETE().build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.warn("mineru cancel failed for {}: {}", jobId, e.getClass().getSimpleName());
        }
    }

    private JsonNode createUpload(String filename, long bytes, String sha256) {
        Map<String, Object> body = Map.of("filename", filename, "bytes", bytes,
                "mime_type", "application/pdf", "purpose", "parse", "sha256sum", sha256);
        JsonNode response = sendJson("POST", "/v1/uploads", body);
        String id = response.path("id").asText("");
        if (id.isBlank()) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE, "mineru upload id missing");
        }
        return response;
    }

    private void putContent(String uploadId, byte[] bytes) {
        try {
            HttpRequest request = request("/v1/uploads/" + uploadId + "/content")
                    .header("Content-Type", "application/pdf")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE,
                        "mineru content upload rejected: " + response.statusCode());
            }
        } catch (RunApiException e) {
            throw e;
        } catch (Exception e) {
            throw new MinerUUnavailableException("mineru content upload failed", e);
        }
    }

    private String completeUpload(String uploadId, String sha256) {
        JsonNode response = sendJson("POST", "/v1/uploads/" + uploadId + "/complete", Map.of("sha256sum", sha256));
        String fileId = response.path("file").path("id").asText("");
        if (fileId.isBlank() || !"completed".equals(response.path("status").asText(""))) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE, "mineru upload did not complete");
        }
        return fileId;
    }

    private String createJob(String fileId) {
        Map<String, Object> body = Map.of(
                "files", List.of(Map.of("source", Map.of("type", "file_id", "file_id", fileId))),
                "tier", properties.getTier(),
                "ocr_mode", properties.getOcrMode(),
                "output_formats", List.of("markdown", "structured_content"));
        JsonNode response = sendJson("POST", "/v1/parse/jobs", body);
        String jobId = response.path("job_id").asText("");
        if (jobId.isBlank()) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE, "mineru job id missing");
        }
        return jobId;
    }

    private JsonNode awaitJob(String jobId, int deadlineSeconds,java.util.function.BooleanSupplier cancelled) {
        Instant deadline = Instant.now().plusSeconds(Math.max(30, deadlineSeconds));
        long backoff = Math.max(500, properties.getPollIntervalMs());
        while (Instant.now().isBefore(deadline)) {
            if(cancelled.getAsBoolean()) {cancel(jobId);throw new ParseCancelledException();}
            JsonNode job = getJob(jobId);
            String status = job.path("status").asText("");
            if (TERMINAL_STATUSES.contains(status)) {
                return job;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MinerUUnavailableException("mineru poll interrupted", e);
            }
            backoff = Math.min(backoff + 500, 5000);
        }
        throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE, "mineru parse deadline exceeded");
    }

    private static final java.util.Set<String> TERMINAL_STATUSES = java.util.Set.of("completed", "partial", "failed", "canceled");

    private JsonNode getJob(String jobId) {
        try {
            HttpRequest request = request("/v1/parse/jobs/" + jobId).GET()
                    .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds())).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                throw new JobLostException(jobId);
            }
            if (response.statusCode() / 100 != 2) {
                throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE,
                        "mineru job query rejected: " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (JobLostException | RunApiException e) {
            throw e;
        } catch (Exception e) {
            throw new MinerUUnavailableException("mineru job query failed", e);
        }
    }

    private byte[] downloadFile(String fileId) {
        try {
            HttpRequest request = request("/v1/files/" + fileId + "/content").GET()
                    .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds())).build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE,
                        "mineru output download rejected: " + response.statusCode());
            }
            return response.body();
        } catch (RunApiException e) {
            throw e;
        } catch (Exception e) {
            throw new MinerUUnavailableException("mineru output download failed", e);
        }
    }

    private JsonNode sendJson(String method, String path, Object body) {
        try {
            String payload = objectMapper.writeValueAsString(body);
            HttpRequest request = request(path)
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE,
                        "mineru request rejected: " + method + " " + path + " -> " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (RunApiException e) {
            throw e;
        } catch (Exception e) {
            throw new MinerUUnavailableException("mineru request failed: " + path, e);
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(properties.getBaseUrl() + path))
                .header("Authorization", "Bearer " + properties.getToken());
    }

    private static String sha256Hex(byte[] content) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
