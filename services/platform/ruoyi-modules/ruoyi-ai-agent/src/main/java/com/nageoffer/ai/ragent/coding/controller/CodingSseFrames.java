package com.nageoffer.ai.ragent.coding.controller;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE frame shape for the coding harness.
 *
 * <p>The platform gateway strips a leading {@code : ai-delivery <permit> <operation>} comment
 * from every frame and fail-closes on any data/id/event frame without it. The metadata comment
 * must therefore be part of the same frame as the payload; a bare {@code id/event/data} frame is
 * rejected as {@code unprotected stream frame} by both local and HTTP transports.
 */
public final class CodingSseFrames {

    private static final String DELIVERY_COMMENT_PREFIX = " ai-delivery ";

    private CodingSseFrames() {
    }

    public static SseEmitter.SseEventBuilder event(String permitId, String operationId,
                                                   long sequence, String type, String json) {
        requireDeliveryId(permitId);
        requireDeliveryId(operationId);
        if (sequence < 0) {
            throw new IllegalArgumentException("SSE sequence must be non-negative");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("SSE event type is required");
        }
        return SseEmitter.event()
                .comment(DELIVERY_COMMENT_PREFIX + permitId + " " + operationId)
                .id(Long.toString(sequence))
                .name(type)
                .data(json == null ? "" : json, MediaType.APPLICATION_JSON);
    }

    static String deliveryComment(String permitId, String operationId) {
        requireDeliveryId(permitId);
        requireDeliveryId(operationId);
        return DELIVERY_COMMENT_PREFIX + permitId + " " + operationId;
    }

    private static void requireDeliveryId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid delivery permit metadata");
        }
    }
}
