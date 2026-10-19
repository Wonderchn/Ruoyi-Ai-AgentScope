package com.nageoffer.ai.ragent.coding.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Workspace boundary guard (ported from the legacy WorkspaceGuard).
 *
 * <p>Lexical range check first, then a real-path check against the nearest existing ancestor so
 * symlinks, junctions and reparse points cannot escape. Any inability to establish the real path
 * fails closed.
 */
public final class WorkspaceGuard {

    private WorkspaceGuard() {
    }

    public static boolean isWithinWorkspace(Path root, Path target) {
        if (root == null || target == null) {
            return false;
        }
        try {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path normalizedTarget = target.isAbsolute()
                    ? target.toAbsolutePath().normalize()
                    : normalizedRoot.resolve(target).normalize();

            if (!normalizedTarget.startsWith(normalizedRoot)) {
                return false;
            }

            Path realRoot = normalizedRoot.toRealPath().normalize();
            Path nearestExisting = nearestExistingAncestor(normalizedTarget);
            Path realExisting = nearestExisting.toRealPath().normalize();
            return realExisting.startsWith(realRoot);
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    private static Path nearestExistingAncestor(Path target) throws IOException {
        Path current = target;
        while (current != null) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IOException("Target has no existing ancestor: " + target);
    }
}
