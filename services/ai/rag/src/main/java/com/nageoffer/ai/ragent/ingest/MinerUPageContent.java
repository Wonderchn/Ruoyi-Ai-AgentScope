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

import java.util.ArrayList;
import java.util.List;

/** Page provenance comes exclusively from the local parser's full-document artifact. */
public final class MinerUPageContent {
    private MinerUPageContent() { }

    public record Page(int number, String markdown) { }

    public static List<Page> decode(String artifact, ObjectMapper mapper) {
        try {
            JsonNode root = mapper.readTree(artifact);
            JsonNode pages = root.path("pages");
            JsonNode pageCount = root.path("metadata").path("document").path("page_count");
            if (!root.path("is_full_document").asBoolean(false) || !pages.isArray()
                    || !pageCount.isIntegralNumber() || !pageCount.canConvertToInt() || pageCount.asInt() < 1 || pageCount.asInt() > 10000
                    || pages.size() != pageCount.asInt()) throw invalid();
            List<Page> result = new ArrayList<>();
            for (int index = 0; index < pages.size(); index++) {
                JsonNode page = pages.get(index);
                JsonNode sourceIndex = page.path("page_idx");
                if (!sourceIndex.isIntegralNumber() || !sourceIndex.canConvertToInt() || sourceIndex.asInt() != index
                        || !page.path("blocks").isArray()) throw invalid();
                StringBuilder markdown = new StringBuilder();
                for (JsonNode block : page.path("blocks")) {
                    if (!block.isObject()) throw invalid();
                    appendText(markdown, block.path("content"));
                    for (String name : List.of("captions", "footnotes")) {
                        JsonNode annotations = block.path(name);
                        if (!annotations.isMissingNode() && !annotations.isArray()) throw invalid();
                        for (JsonNode annotation : annotations) appendText(markdown, annotation.path("content"));
                    }
                }
                result.add(new Page(index + 1, markdown.toString()));
            }
            return List.copyOf(result);
        } catch (RunApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private static void appendText(StringBuilder output, JsonNode content) {
        if (!content.isTextual()) throw invalid();
        String text = content.asText().strip();
        if (!text.isEmpty()) {
            if (!output.isEmpty()) output.append("\n\n");
            output.append(text);
        }
    }

    private static RunApiException invalid() {
        return new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE, "mineru structured page provenance invalid");
    }
}
