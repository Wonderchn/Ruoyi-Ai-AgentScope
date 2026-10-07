package com.nageoffer.ai.ragent.authorization.dao;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/** Tenant and composite resource keys are bound parameters, including for mixed-type batches. */
record ResourceBatchQuery(String predicate, Map<String, Object> parameters) {
    static ResourceBatchQuery of(String tenantId, Map<String, ? extends Collection<String>> idsByType) {
        ExecutionPrincipal.requireTenantId(tenantId);
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("tenantId", tenantId);
        var groups = new StringJoiner(" OR ");
        int index = 0;
        for (var entry : idsByType.entrySet()) {
            if (entry.getValue().isEmpty()) { continue; }
            String type = "type" + index, ids = "ids" + index++;
            groups.add("(resource_type = :" + type + " AND resource_id IN (:" + ids + "))");
            parameters.put(type, entry.getKey());
            parameters.put(ids, entry.getValue());
        }
        return new ResourceBatchQuery(groups.length() == 0 ? "FALSE"
                : "(" + groups + ")", parameters);
    }
}
