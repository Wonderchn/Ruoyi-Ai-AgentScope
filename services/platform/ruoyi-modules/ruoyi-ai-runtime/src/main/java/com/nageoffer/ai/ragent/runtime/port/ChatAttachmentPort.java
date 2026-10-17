package com.nageoffer.ai.ragent.runtime.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.runtime.config.EngineModelAuthority;
import com.nageoffer.ai.ragent.runtime.config.RunConfigBinding;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import java.util.List;
import java.util.Map;

/** Identity must come from principal/run facts, never attachment payload fields. */
public interface ChatAttachmentPort {
    void validateAdmission(ExecutionPrincipal principal,AdmissionRequest request,EngineModelAuthority.PublishedModel model);
    List<ChatMessage> prepare(RunRecord run,RunConfigBinding binding,List<ChatMessage> messages);
    void bind(String tenant,String member,String user,String kind,String conversation,String message,JsonNode refs);
    List<Map<String,Object>> list(String tenant,String member,String user,String kind,String conversation,String message);
    void persistAgentTurn(RunRecord run,String question,String answer);
    static boolean hasAttachments(JsonNode input) { return input!=null && input.has("attachments")
            && (!input.path("attachments").isArray() || !input.path("attachments").isEmpty()); }
}
