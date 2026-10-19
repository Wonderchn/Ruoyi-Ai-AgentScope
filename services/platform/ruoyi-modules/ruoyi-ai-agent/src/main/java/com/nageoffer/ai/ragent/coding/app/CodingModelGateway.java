package com.nageoffer.ai.ragent.coding.app;

/** Model port for the coding harness. Implementations must not leak provider details. */
@FunctionalInterface
public interface CodingModelGateway {

    ModelTurn complete(CodingModelRequest request) throws CodingModelUnavailableException;
}
