package com.nageoffer.ai.ragent.coding.app;

import com.nageoffer.ai.ragent.coding.model.HarnessMessage;
import com.nageoffer.ai.ragent.coding.model.HarnessOwner;
import com.nageoffer.ai.ragent.coding.model.HarnessRunState;
import com.nageoffer.ai.ragent.coding.model.HarnessSessionState;
import com.nageoffer.ai.ragent.coding.tool.CodingToolDescriptor;

import java.util.List;

/** Immutable model invocation request. The gateway never receives credentials. */
public record CodingModelRequest(
        HarnessOwner owner,
        HarnessSessionState session,
        HarnessRunState run,
        List<HarnessMessage> transcript,
        List<CodingToolDescriptor> tools
) {

    public CodingModelRequest {
        transcript = transcript == null ? List.of() : List.copyOf(transcript);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
