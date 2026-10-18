package com.nageoffer.ai.ragent.coding.tool;

import com.nageoffer.ai.ragent.coding.sandbox.DockerSandboxCommandBuilder;
import com.nageoffer.ai.ragent.coding.sandbox.DockerSandboxConfig;
import com.nageoffer.ai.ragent.coding.sandbox.SandboxCommandException;
import com.nageoffer.ai.ragent.coding.workspace.CodingWorkspaceService;
import com.nageoffer.ai.ragent.coding.workspace.WorkspaceGuard;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Minimal built-in coding tool set: {@code read_file}, {@code write_file}, {@code list_dir},
 * {@code execute_process}.
 *
 * <p>File operations are always confined to the session workspace by {@link WorkspaceGuard}.
 * Command execution is only ever a pinned, networkless Docker invocation built by
 * {@link DockerSandboxCommandBuilder}; when the sandbox is not fully configured the call fails
 * with {@code SANDBOX_NOT_CONFIGURED} before any host process starts. Shell command strings are
 * not accepted — argv arrays only.
 */
public class CodingToolRuntime {

    private static final int MAX_OUTPUT_BYTES = 64 * 1024;
    private static final long DEFAULT_TIMEOUT_MS = 30_000;
    private static final long MAX_TIMEOUT_MS = 120_000;

    private final CodingWorkspaceService workspaceService;
    private final DockerSandboxConfig sandbox;
    private final boolean executeProcessEnabled;

    public CodingToolRuntime(CodingWorkspaceService workspaceService, DockerSandboxConfig sandbox,
                             boolean executeProcessEnabled) {
        this.workspaceService = workspaceService;
        this.sandbox = sandbox == null ? DockerSandboxConfig.UNCONFIGURED : sandbox;
        this.executeProcessEnabled = executeProcessEnabled;
    }

    public List<CodingToolDescriptor> descriptors() {
        List<CodingToolDescriptor> descriptors = new ArrayList<>();
        descriptors.add(new CodingToolDescriptor("read_file", CodingToolDescriptor.READ, false));
        descriptors.add(new CodingToolDescriptor("list_dir", CodingToolDescriptor.READ, false));
        descriptors.add(new CodingToolDescriptor("write_file", CodingToolDescriptor.WRITE, true));
        descriptors.add(new CodingToolDescriptor("execute_process", CodingToolDescriptor.WRITE, true));
        return List.copyOf(descriptors);
    }

    public ToolExecutionResult execute(String toolName, Map<String, Object> arguments,
                                       Path workspace, String runId,
                                       java.util.Set<String> authorizedActions) {
        if (toolName == null || toolName.isBlank()) {
            return ToolExecutionResult.error("unknown", "UNKNOWN_TOOL", "tool name is required");
        }
        String requiredAction = requiredAction(toolName);
        if (requiredAction == null) {
            return ToolExecutionResult.error(toolName, "UNKNOWN_TOOL",
                    "Unknown coding tool: " + toolName);
        }
        // Scope check precedes approval/execution: a client-chosen approvalPolicy=NEVER can never
        // grant a capability the caller does not hold.
        if (authorizedActions == null || !authorizedActions.contains(requiredAction)) {
            return ToolExecutionResult.error(toolName, "FORBIDDEN",
                    "The run is not authorized for " + requiredAction);
        }
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        try {
            return switch (toolName) {
                case "read_file" -> readFile(args, workspace);
                case "write_file" -> writeFile(args, workspace);
                case "list_dir" -> listDir(args, workspace);
                case "execute_process" -> executeProcess(args, workspace, runId);
                default -> ToolExecutionResult.error(toolName, "UNKNOWN_TOOL",
                        "Unknown coding tool: " + toolName);
            };
        } catch (SandboxCommandException sandboxFailure) {
            return ToolExecutionResult.error(toolName, sandboxFailure.code(), sandboxFailure.getMessage());
        } catch (IllegalArgumentException invalidArguments) {
            return ToolExecutionResult.error(toolName, "INVALID_ARGUMENT",
                    invalidArguments.getMessage());
        }
    }

    /** Canonical action required by a built-in tool; {@code null} for unknown tools. */
    public static String requiredAction(String toolName) {
        if (toolName == null) {
            return null;
        }
        return switch (toolName) {
            case "read_file", "list_dir" -> "coding.use";
            case "write_file" -> "coding.write";
            case "execute_process" -> "coding.command";
            default -> null;
        };
    }

    public CommandExecutionResult executeCommand(Path workspace, String executable,
                                                 List<String> argv, String cwd, Long timeoutMs) {
        if (!executeProcessEnabled) {
            throw new SandboxCommandException("SANDBOX_NOT_CONFIGURED",
                    "execute_process is disabled; no command was started");
        }
        sandbox.requireUsableDockerExecutable();
        Path workingDirectory = resolveWorkingDirectory(workspace, cwd);
        List<String> arguments = argv == null ? List.of() : argv;
        List<String> command = DockerSandboxCommandBuilder.hardenedArguments(sandbox, workspace,
                executable, arguments, containerWorkingDirectory(workspace, workingDirectory));
        long timeout = normalizeTimeout(timeoutMs);
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workspace.toFile());
            builder.redirectErrorStream(false);
            process = builder.start();
            Process running = process;
            BoundedReader stdout = new BoundedReader(running.getInputStream());
            BoundedReader stderr = new BoundedReader(running.getErrorStream());
            Thread outThread = new Thread(stdout, "coding-sandbox-stdout");
            Thread errThread = new Thread(stderr, "coding-sandbox-stderr");
            outThread.setDaemon(true);
            errThread.setDaemon(true);
            outThread.start();
            errThread.start();
            boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                outThread.join(1_000);
                errThread.join(1_000);
                return new CommandExecutionResult(executable, arguments, -1,
                        stdout.text(), stderr.text(), true);
            }
            outThread.join(2_000);
            errThread.join(2_000);
            return new CommandExecutionResult(executable, arguments, process.exitValue(),
                    stdout.text(), stderr.text(), false);
        } catch (IOException error) {
            throw new SandboxCommandException("DOCKER_RUNTIME_UNAVAILABLE",
                    "Cannot start the sandboxed command", error);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new SandboxCommandException("PROCESS_INTERRUPTED",
                    "Sandboxed command was interrupted", interrupted);
        }
    }

    private ToolExecutionResult readFile(Map<String, Object> args, Path workspace) {
        String path = string(args.get("path"));
        CodingWorkspaceService.FileContent content = workspaceService.read(workspace, path);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", content.path());
        data.put("content", content.content());
        data.put("size", content.size());
        return ToolExecutionResult.ok("read_file", data);
    }

    private ToolExecutionResult writeFile(Map<String, Object> args, Path workspace) {
        String path = string(args.get("path"));
        String content = string(args.get("content"));
        CodingWorkspaceService.FileContent written = workspaceService.write(workspace, path, content);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", written.path());
        data.put("size", written.size());
        return ToolExecutionResult.ok("write_file", data);
    }

    private ToolExecutionResult listDir(Map<String, Object> args, Path workspace) {
        String relative = string(args.getOrDefault("path", "."));
        Path target = workspace.resolve(relative).normalize();
        if (!WorkspaceGuard.isWithinWorkspace(workspace, target)) {
            throw new IllegalArgumentException("Directory must be inside the workspace");
        }
        if (!Files.isDirectory(target)) {
            throw new IllegalArgumentException("Directory does not exist: " + relative);
        }
        CodingWorkspaceService.WorkspaceResult listing = workspaceService.list(target);
        List<Map<String, Object>> entries = listing.files().stream()
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("path", entry.path());
                    row.put("name", entry.name());
                    row.put("directory", entry.directory());
                    row.put("size", entry.size());
                    return row;
                })
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("root", listing.root());
        data.put("fileCount", listing.fileCount());
        data.put("entries", entries);
        return ToolExecutionResult.ok("list_dir", data);
    }

    private ToolExecutionResult executeProcess(Map<String, Object> args, Path workspace,
                                               String runId) {
        String executable = string(args.get("executable"));
        List<String> argv = stringList(args.get("argv"));
        String cwd = args.get("cwd") == null ? null : string(args.get("cwd"));
        Long timeoutMs = args.get("timeoutMs") instanceof Number number
                ? number.longValue() : null;
        CommandExecutionResult result = executeCommand(workspace, executable, argv, cwd, timeoutMs);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("executable", result.executable());
        data.put("argv", result.argv());
        data.put("exitCode", result.exitCode());
        data.put("stdout", result.stdout());
        data.put("stderr", result.stderr());
        data.put("timedOut", result.timedOut());
        data.put("runId", runId);
        return result.success() ? ToolExecutionResult.ok("execute_process", data)
                : new ToolExecutionResult("execute_process", true,
                result.timedOut() ? "PROCESS_TIMEOUT" : "PROCESS_EXIT_NONZERO", data);
    }

    private Path resolveWorkingDirectory(Path workspace, String cwd) {
        Path target = cwd == null || cwd.isBlank()
                ? workspace : workspace.resolve(cwd).normalize();
        if (!WorkspaceGuard.isWithinWorkspace(workspace, target)) {
            throw new SandboxCommandException("CWD_OUTSIDE_WORKSPACE",
                    "cwd escapes the immutable workspace lease");
        }
        if (!Files.isDirectory(target)) {
            throw new SandboxCommandException("CWD_NOT_FOUND",
                    "cwd must be an existing workspace directory");
        }
        return target;
    }

    /** Maps a validated workspace directory to the single bind mount's container path. */
    private static String containerWorkingDirectory(Path workspace, Path workingDirectory) {
        Path relative = workspace.toAbsolutePath().normalize()
                .relativize(workingDirectory.toAbsolutePath().normalize());
        if (relative.toString().isEmpty()) {
            return DockerSandboxCommandBuilder.CONTAINER_WORKSPACE;
        }
        StringBuilder path = new StringBuilder(DockerSandboxCommandBuilder.CONTAINER_WORKSPACE);
        for (Path segment : relative) {
            path.append('/').append(segment.toString().replace('\\', '/'));
        }
        return path.toString();
    }

    private static long normalizeTimeout(Long timeoutMs) {
        if (timeoutMs == null || timeoutMs <= 0) {
            return DEFAULT_TIMEOUT_MS;
        }
        return Math.min(timeoutMs, MAX_TIMEOUT_MS);
    }

    private static String string(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("argument is required");
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            throw new IllegalArgumentException("argument must not be blank");
        }
        return text;
    }

    private static List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("argv must be an array");
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item == null) {
                throw new IllegalArgumentException("argv must not contain null");
            }
            result.add(String.valueOf(item));
        }
        return result;
    }

    /** Reads at most {@link #MAX_OUTPUT_BYTES} without blocking process exit. */
    private static final class BoundedReader implements Runnable {
        private final InputStream input;
        private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();

        private BoundedReader(InputStream input) {
            this.input = input;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[4096];
            try {
                int read;
                while ((read = input.read(chunk)) >= 0) {
                    if (buffer.size() < MAX_OUTPUT_BYTES) {
                        int allowed = Math.min(read, MAX_OUTPUT_BYTES - buffer.size());
                        buffer.write(chunk, 0, allowed);
                    }
                }
            } catch (IOException ignored) {
                // The process was terminated; already collected output is the honest result.
            }
        }

        private String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
