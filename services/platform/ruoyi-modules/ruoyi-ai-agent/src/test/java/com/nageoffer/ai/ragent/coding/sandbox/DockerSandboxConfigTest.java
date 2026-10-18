package com.nageoffer.ai.ragent.coding.sandbox;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F20-A op5 沙箱 fail-closed 判据：缺任一配置零执行、非 root、镜像 sha256、
 * pids/mem/cpu/tmpfs 限额以及 argv 硬化标志。
 */
@Tag("dev")
class DockerSandboxConfigTest {

    @Test
    void unconfiguredSandboxRefusesBeforeAnyExecution() {
        assertThatThrownBy(() -> DockerSandboxConfig.UNCONFIGURED.requireUsableDockerExecutable())
                .isInstanceOf(SandboxCommandException.class)
                .extracting(error -> ((SandboxCommandException) error).code())
                .isEqualTo("SANDBOX_NOT_CONFIGURED");
    }

    @Test
    void everyMissingFieldKeepsTheSandboxClosed(@TempDir Path dir) throws Exception {
        Path executable = Files.createFile(dir.resolve("docker"));
        String sha = "0".repeat(64);
        String host = DockerSandboxConfig.localDockerHost();
        String image = "sha256:" + "a".repeat(64);

        List<DockerSandboxConfig> partial = List.of(
                DockerSandboxConfig.configured("", sha, dir.toString(), host, image),
                DockerSandboxConfig.configured(executable.toString(), "", dir.toString(), host, image),
                DockerSandboxConfig.configured(executable.toString(), sha, "", host, image),
                DockerSandboxConfig.configured(executable.toString(), sha, dir.toString(), "", image),
                DockerSandboxConfig.configured(executable.toString(), sha, dir.toString(), host, ""));

        for (DockerSandboxConfig config : partial) {
            assertThatThrownBy(config::requireUsableDockerExecutable)
                    .isInstanceOf(SandboxCommandException.class)
                    .extracting(error -> ((SandboxCommandException) error).code())
                    .isEqualTo("SANDBOX_NOT_CONFIGURED");
        }
    }

    @Test
    void rejectsMutableImageReferencesAndRootUser() {
        assertThatThrownBy(() -> DockerSandboxConfig.configured("/usr/bin/docker", "a".repeat(64),
                "/tmp", DockerSandboxConfig.localDockerHost(), "ubuntu:latest"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> new DockerSandboxConfig("/usr/bin/docker", "a".repeat(64),
                "/tmp", DockerSandboxConfig.localDockerHost(), "sha256:" + "b".repeat(64),
                256, 1024, 1000, 1024, "0:0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-root");
    }

    @Test
    void exposesBoundedResourceLimits() {
        DockerSandboxConfig config = DockerSandboxConfig.configured("/usr/bin/docker", "a".repeat(64),
                "/tmp", DockerSandboxConfig.localDockerHost(), "sha256:" + "b".repeat(64));
        assertThat(config.pidsLimit()).isEqualTo(256);
        assertThat(config.memoryBytes()).isEqualTo(1024L * 1024 * 1024);
        assertThat(config.cpuMilliCores()).isEqualTo(2000);
        assertThat(config.tmpfsBytes()).isEqualTo(64L * 1024 * 1024);
        assertThat(config.user()).isEqualTo("65532:65532");
        assertThat(config.cpuLimit()).isEqualTo("2.000");
    }

    @Test
    void hardenedDockerRunArgumentsAreBuiltWithoutStartingAnythingDockerSandbox() {
        DockerSandboxConfig config = DockerSandboxConfig.configured("/usr/bin/docker", "a".repeat(64),
                "/tmp", DockerSandboxConfig.localDockerHost(), "sha256:" + "b".repeat(64));

        List<String> args = DockerSandboxCommandBuilder.hardenedArguments(config,
                Path.of("/tmp/ws"), "sh", List.of("-c", "true"));

        assertThat(args).contains("--read-only", "--network", "none", "--cap-drop", "ALL",
                "--pids-limit", "256", "--memory", Long.toString(1024L * 1024 * 1024),
                "--cpus", "2.000", "--user", "65532:65532");
        assertThat(args).containsSequence("--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size="
                + (64L * 1024 * 1024));
        assertThat(args).contains("--pull", "never");
        assertThat(args).contains(config.pinnedImage());
        assertThat(args).doesNotContain("--privileged");
    }

    @Test
    void commandRejectsCommasAndControlCharactersInPaths() {
        assertThatThrownBy(() -> DockerSandboxConfig.rejectControlOrComma("/tmp/a,b", "path"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DockerSandboxConfig.rejectControlOrComma("/tmp/a\nb", "path"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
