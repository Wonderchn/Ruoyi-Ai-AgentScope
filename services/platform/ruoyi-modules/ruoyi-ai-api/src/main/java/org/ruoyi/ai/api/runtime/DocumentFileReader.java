package org.ruoyi.ai.api.runtime;


/** Neutral Worker capability contract; implementations belong to the RAG/infra modules. */
public interface DocumentFileReader {
    java.io.InputStream openStream(String key);
}
