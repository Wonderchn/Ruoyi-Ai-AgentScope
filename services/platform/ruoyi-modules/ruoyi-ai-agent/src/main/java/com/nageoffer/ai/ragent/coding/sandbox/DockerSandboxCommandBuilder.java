package com.nageoffer.ai.ragent.coding.sandbox;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Builds one argv-only, networkless Docker invocation with a single workspace bind mount.
 * Ported from the legacy DockerSandboxCommandBuilder; the container is created read-only,
 * non-root, capability-dropped and resource bounded, and the image is never pulled.
 */
public final class DockerSandboxCommandBuilder {

    public static final String CONTAINER_WORKSPACE = "/workspace";
    public static final String CONTAINER_PREFIX = "ruoyi-harness-";

    private DockerSandboxCommandBuilder() {
    }

    /** Pure argv construction: no process starts and the Docker executable is not probed. */
    public static List<String> hardenedArguments(DockerSandboxConfig sandbox, Path workspace,
                                                 String executable, List<String> arguments) {
        return hardenedArguments(sandbox, workspace, executable, arguments, CONTAINER_WORKSPACE);
    }

    /** As above, with an explicit (already validated) container working directory. */
    public static List<String> hardenedArguments(DockerSandboxConfig sandbox, Path workspace,
                                                 String executable, List<String> arguments,
                                                 String containerWorkingDirectory) {
        if (sandbox == null) {
            throw new SandboxCommandException("SANDBOX_NOT_CONFIGURED",
                    "Docker sandbox policy is required");
        }
        if (executable == null || executable.isBlank()) {
            throw new SandboxCommandException("BAD_EXECUTABLE", "executable is required");
        }
        DockerSandboxConfig.rejectControlOrComma(executable, "Container executable");
        Path mount = workspace.toAbsolutePath().normalize();
        DockerSandboxConfig.rejectControlOrComma(mount.toString(), "Workspace path");
        String workdir = containerWorkingDirectory == null || containerWorkingDirectory.isBlank()
                ? CONTAINER_WORKSPACE : containerWorkingDirectory;
        if (!workdir.equals(CONTAINER_WORKSPACE) && !workdir.startsWith(CONTAINER_WORKSPACE + "/")) {
            throw new SandboxCommandException("UNSAFE_WORKDIR",
                    "Container working directory must stay inside " + CONTAINER_WORKSPACE);
        }
        DockerSandboxConfig.rejectControlOrComma(workdir, "Container working directory");

        List<String> args = new ArrayList<>(48 + (arguments == null ? 0 : arguments.size()));
        args.add(sandbox.dockerExecutable());
        args.add("run");
        args.add("--rm");
        args.addAll(List.of(
                "--pull", "never",
                "--platform", "linux",
                "--read-only",
                "--network", "none",
                "--log-driver", "none",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges=true",
                "--pids-limit", Integer.toString(sandbox.pidsLimit()),
                "--memory", Long.toString(sandbox.memoryBytes()),
                "--memory-swap", Long.toString(sandbox.memoryBytes()),
                "--cpus", sandbox.cpuLimit(),
                "--ipc", "none",
                "--cgroupns", "private",
                "--ulimit", "nofile=1024:1024",
                "--ulimit", "core=0",
                "--restart", "no",
                "--stop-timeout", "1",
                "--no-healthcheck",
                "--init",
                "--user", sandbox.user(),
                "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=" + sandbox.tmpfsBytes(),
                "--mount", "type=bind,source=" + mount + ",target=" + CONTAINER_WORKSPACE
                        + ",bind-propagation=rprivate,bind-recursive=disabled",
                "--workdir", workdir,
                "--entrypoint", executable,
                sandbox.pinnedImage()));
        if (arguments != null) {
            for (String argument : arguments) {
                if (argument == null) {
                    throw new SandboxCommandException("BAD_ARGUMENT", "argv must not contain null");
                }
                args.add(argument);
            }
        }
        return List.copyOf(args);
    }

    public static String uniqueContainerName(String runId) {
        String source = (runId == null ? "" : runId) + ':' + UUID.randomUUID();
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
            return CONTAINER_PREFIX + digest.substring(0, 32);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
