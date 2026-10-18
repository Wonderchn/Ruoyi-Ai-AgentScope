package com.nageoffer.ai.ragent.coding.runtime;

import java.util.ArrayList;
import java.util.List;

/** Read-only model directory exposed by {@code GET /coding/models}. */
public record CodingModelCatalog(List<String> models) {

    public CodingModelCatalog {
        models = models == null ? List.of() : List.copyOf(models);
    }

    public static CodingModelCatalog fromCsv(String csv) {
        List<String> models = new ArrayList<>();
        if (csv != null) {
            for (String value : csv.split(",")) {
                String normalized = value.strip();
                if (!normalized.isEmpty() && !models.contains(normalized)) {
                    models.add(normalized);
                }
            }
        }
        return new CodingModelCatalog(models);
    }
}
