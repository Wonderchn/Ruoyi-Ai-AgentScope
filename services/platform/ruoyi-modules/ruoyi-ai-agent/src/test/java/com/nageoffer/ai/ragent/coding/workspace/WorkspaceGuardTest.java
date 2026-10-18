package com.nageoffer.ai.ragent.coding.workspace;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F20-A op1/op5 工作区边界判据：词法穿越、绝对路径逃逸、软链接逃逸必须 fail-closed。
 */
@Tag("dev")
class WorkspaceGuardTest {

    @Test
    void acceptsTargetsInsideWorkspace(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectories(root.resolve("ws"));
        Path child = Files.createDirectories(workspace.resolve("a/b"));

        assertThat(WorkspaceGuard.isWithinWorkspace(workspace, child.resolve("file.txt"))).isTrue();
        assertThat(WorkspaceGuard.isWithinWorkspace(workspace,
                workspace.resolve("new/dir/file.txt"))).isTrue();
    }

    @Test
    void rejectsLexicalTraversalAndAbsoluteOutsidePaths(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectories(root.resolve("ws"));
        Path outside = Files.createDirectories(root.resolve("outside"));

        assertThat(WorkspaceGuard.isWithinWorkspace(workspace, workspace.resolve("../outside/evil"))).isFalse();
        assertThat(WorkspaceGuard.isWithinWorkspace(workspace, outside.resolve("evil"))).isFalse();
        assertThat(WorkspaceGuard.isWithinWorkspace(workspace, workspace.resolve(".."))).isFalse();
    }

    @Test
    void rejectsSymlinkEscapeWhenSupported(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectories(root.resolve("ws"));
        Path outside = Files.createDirectories(root.resolve("outside"));
        Path link = workspace.resolve("escape");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            Assumptions.assumeTrue(false, "symbolic links are not permitted for this test user");
        }

        assertThat(WorkspaceGuard.isWithinWorkspace(workspace, link.resolve("evil.txt"))).isFalse();
    }

    @Test
    void missingWorkspaceFailsClosed(@TempDir Path root) {
        assertThat(WorkspaceGuard.isWithinWorkspace(root.resolve("absent"), root.resolve("absent/x"))).isFalse();
        assertThat(WorkspaceGuard.isWithinWorkspace(null, root.resolve("x"))).isFalse();
        assertThat(WorkspaceGuard.isWithinWorkspace(root, null)).isFalse();
    }
}
