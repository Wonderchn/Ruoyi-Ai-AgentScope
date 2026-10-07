package org.ruoyi.ai.api.runtime;

import java.util.List;

/** Authorized scope is supplied by runtime; the adapter cannot replace it with request collections. */
public interface AuthorizedRetrievalPort<S, R> {
    List<R> retrieve(S scope, String query, int topK);
}
