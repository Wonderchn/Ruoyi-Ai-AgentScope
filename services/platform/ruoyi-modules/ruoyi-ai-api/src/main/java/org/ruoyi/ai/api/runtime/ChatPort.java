package org.ruoyi.ai.api.runtime;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Neutral Worker capability contract; implementations belong to the RAG/infra modules. */
public interface ChatPort<M> {
    String provider();
    String model();
    ChatResult stream(List<M> messages, int maxTokens, Consumer<String> onDelta);
    record ChatResult(String content, String providerRequestId, Map<String, Object> usageRaw, String finishReason) { }
}
