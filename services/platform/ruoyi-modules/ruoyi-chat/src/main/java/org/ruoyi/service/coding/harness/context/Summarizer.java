package org.ruoyi.service.coding.harness.context;

/** Provider adapter boundary. ContextEngine itself never performs a model call. */
@FunctionalInterface
public interface Summarizer {

    SummaryDraft summarize(SummaryRequest request) throws Exception;
}
