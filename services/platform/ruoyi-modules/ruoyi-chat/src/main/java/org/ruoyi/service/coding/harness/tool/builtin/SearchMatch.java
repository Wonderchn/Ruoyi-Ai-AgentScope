package org.ruoyi.service.coding.harness.tool.builtin;

/** One line-oriented text-search match. */
public record SearchMatch(
    String path,
    int line,
    int column,
    String text,
    boolean lineTruncated
) {
}
