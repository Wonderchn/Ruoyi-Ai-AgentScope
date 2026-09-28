package org.ruoyi.service.coding.harness.artifact;

public record ArtifactReadResult(
    String artifactId,
    long offset,
    int byteLength,
    String content
) { }
