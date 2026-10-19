package com.nageoffer.ai.ragent.coding.controller;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B1 传输层帧形状判据：每个数据帧必须携带 {@code : ai-delivery <permit> <operation>}，
 * 否则本地/HTTP 网关按 {@code unprotected stream frame} fail-closed。
 *
 * <p>这里直接检查 {@link CodingSseFrames} 生成的帧部件，并用与网关相同的行级判定复算一次
 * （{@code LocalAiGatewayClient.deliverFrame} / {@code AiGatewayClient.deliverFrame}）。
 */
@Tag("dev")
class CodingSseFrameShapeTest {

    @Test
    void everyDataFrameCarriesDeliveryMetadataBeforePayload() {
        Set<ResponseBodyEmitter.DataWithMediaType> parts = CodingSseFrames
                .event("permit-1", "op-1", 7, "run.started", "{\"type\":\"run.started\"}")
                .build();

        String rendered = render(parts);
        assertThat(rendered).contains(": ai-delivery permit-1 op-1\n");
        assertThat(rendered.indexOf(": ai-delivery permit-1 op-1"))
                .isLessThan(rendered.indexOf("id:7"));
        assertThat(rendered).contains("id:7\n", "event:run.started\n",
                "data:{\"type\":\"run.started\"}\n");
        assertThat(gatewayWouldAccept(rendered)).isTrue();
    }

    @Test
    void aBareFrameIsRejectedByTheSameGatewayRule() {
        String bare = "id:1\nevent:run.started\ndata:{}\n\n";
        assertThat(gatewayWouldAccept(bare)).isFalse();
    }

    @Test
    void invalidPermitMetadataIsRejectedBeforeSending() {
        assertThatThrownBy(() -> CodingSseFrames.event("", "op-1", 1, "run.started", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CodingSseFrames.event("permit 1", "op-1", 1, "run.started", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CodingSseFrames.event("permit-1", "op/1", 1, "run.started", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CodingSseFrames.event("permit-1", "op-1", -1, "run.started", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Renders an SSE frame the same way Spring's SseEmitter does: raw part concatenation. */
    private static String render(Set<ResponseBodyEmitter.DataWithMediaType> parts) {
        StringBuilder frame = new StringBuilder();
        for (ResponseBodyEmitter.DataWithMediaType part : parts) {
            Object data = part.getData();
            if (data != null) {
                frame.append(data);
            }
        }
        return frame.toString();
    }

    /** Mirrors the transport parser: data/id/event require exactly one delivery comment. */
    private static boolean gatewayWouldAccept(String frame) {
        String permit = null;
        String operation = null;
        boolean restricted = false;
        for (String line : frame.split("\n", -1)) {
            if (line.startsWith(": ai-delivery ")) {
                String[] ids = line.substring(14).trim().split(" ", -1);
                if (permit != null || ids.length != 2
                        || !ids[0].matches("[A-Za-z0-9_-]{1,128}")
                        || !ids[1].matches("[A-Za-z0-9_-]{1,128}")) {
                    return false;
                }
                permit = ids[0];
                operation = ids[1];
            } else if (line.startsWith("data:") || line.startsWith("id:")
                    || line.startsWith("event:")) {
                restricted = true;
            }
        }
        return !restricted || (permit != null && operation != null);
    }
}
