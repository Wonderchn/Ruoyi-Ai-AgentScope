package com.nageoffer.ai.ragent.coding.app;

/** The default port fails closed: no provider call is attempted. */
public class UnconfiguredCodingModelGateway implements CodingModelGateway {

    @Override
    public ModelTurn complete(CodingModelRequest request) {
        throw new CodingModelUnavailableException("coding model gateway is not configured");
    }
}
