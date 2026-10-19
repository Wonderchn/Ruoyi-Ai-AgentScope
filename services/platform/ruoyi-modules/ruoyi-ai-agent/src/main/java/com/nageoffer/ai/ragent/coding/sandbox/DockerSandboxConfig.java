package com.nageoffer.ai.ragent.coding.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Immutable Docker/OCI isolation policy for one {@code execute_process} container.
 *
 * <p>Ported from the legacy DockerSandboxConfig. Missing/partial configuration is
 * {@link #UNCONFIGURED} and every attempt to use it fails with
 * {@code SANDBOX_NOT_CONFIGURED} <b>before any process starts</b>. The pinned image must be an
 * immutable {@code sha256:<64 hex>} local image id, the container runs as a non-root uid:gid,
 * and pids/memory/cpu/tmpfs limits are mandatory.
 *
 * <p>Deliberate simplification: the ACL/owner trust verification for the Docker CLI and config
 * directory is reduced to absolute-path, regular-file, no-symlink and empty-directory checks plus
 * the executable digest. ACL introspection remains a documented follow-up.
 */
public record DockerSandboxConfig(
        String dockerExecutable,
        String dockerExecutableSha256,
        String dockerConfigDirectory,
        String dockerHost,
        String pinnedImage,
        int pidsLimit,
        long memoryBytes,
        int cpuMilliCores,
        long tmpfsBytes,
        String user
) {

    private static final Pattern PINNED_IMAGE = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern USER = Pattern.compile("[1-9][0-9]{0,9}:[1-9][0-9]{0,9}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final String WINDOWS_LOCAL_HOST = "npipe:////./pipe/docker_engine";
    private static final String UNIX_LOCAL_HOST = "unix:///var/run/docker.sock";

    public static final DockerSandboxConfig UNCONFIGURED = new DockerSandboxConfig(
            "", "", "", "", "", 256, 1024L * 1024 * 1024, 2_000, 64L * 1024 * 1024,
            "65532:65532");

    public DockerSandboxConfig {
        dockerExecutable = dockerExecutable == null ? "" : dockerExecutable;
        dockerExecutableSha256 = dockerExecutableSha256 == null
                ? "" : dockerExecutableSha256.toLowerCase(Locale.ROOT);
        dockerConfigDirectory = dockerConfigDirectory == null ? "" : dockerConfigDirectory;
        dockerHost = dockerHost == null ? "" : dockerHost;
        pinnedImage = pinnedImage == null ? "" : pinnedImage;
        user = user == null ? "" : user;
        if (pidsLimit <= 0 || memoryBytes <= 0 || cpuMilliCores <= 0 || tmpfsBytes <= 0
                || !USER.matcher(user).matches()) {
            throw new IllegalArgumentException(
                    "Docker sandbox resource limits and non-root user are required");
        }
        if (!pinnedImage.isEmpty() && !PINNED_IMAGE.matcher(pinnedImage).matches()) {
            throw new IllegalArgumentException(
                    "Docker sandbox image must be an evaluator-approved immutable local image id");
        }
        if (!dockerExecutableSha256.isEmpty()
                && !SHA256.matcher(dockerExecutableSha256).matches()) {
            throw new IllegalArgumentException(
                    "Docker executable SHA-256 must be exactly 64 lowercase hex characters");
        }
        rejectControlOrComma(dockerExecutable, "Docker executable path");
        rejectControlOrComma(dockerExecutableSha256, "Docker executable SHA-256");
        rejectControlOrComma(dockerConfigDirectory, "Docker config directory");
        rejectControlOrComma(dockerHost, "Docker host endpoint");
        rejectControlOrComma(pinnedImage, "Docker image reference");
        if (!dockerHost.isEmpty() && !localDockerHost().equals(dockerHost)) {
            throw new IllegalArgumentException(
                    "Docker sandbox host must be the platform-local engine endpoint");
        }
    }

    public static DockerSandboxConfig configured(String dockerExecutable,
                                                 String dockerExecutableSha256,
                                                 String dockerConfigDirectory,
                                                 String dockerHost, String pinnedImage) {
        return new DockerSandboxConfig(dockerExecutable, dockerExecutableSha256,
                dockerConfigDirectory, dockerHost, pinnedImage, 256,
                1024L * 1024 * 1024, 2_000, 64L * 1024 * 1024, "65532:65532");
    }

    public static String localDockerHost() {
        return isWindows() ? WINDOWS_LOCAL_HOST : UNIX_LOCAL_HOST;
    }

    /** Fails before any process can start when the OS sandbox is not fully configured. */
    public Path requireUsableDockerExecutable() {
        if (dockerExecutable.isBlank() || dockerExecutableSha256.isBlank()
                || dockerConfigDirectory.isBlank() || dockerHost.isBlank()
                || pinnedImage.isBlank()) {
            throw new SandboxCommandException("SANDBOX_NOT_CONFIGURED",
                    "execute_process requires an absolute Docker executable, empty config, "
                            + "local engine endpoint, and pinned image");
        }
        final Path path;
        try {
            path = Path.of(dockerExecutable);
        } catch (InvalidPathException error) {
            throw new SandboxCommandException("DOCKER_RUNTIME_UNAVAILABLE",
                    "Configured Docker executable path is invalid", error);
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!path.isAbsolute()
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalized)
                || (!isWindows() && !Files.isExecutable(normalized))) {
            throw new SandboxCommandException("DOCKER_RUNTIME_UNAVAILABLE",
                    "Configured Docker executable is not an available absolute regular file");
        }
        try {
            if (!normalized.toRealPath().equals(normalized)) {
                throw new SandboxCommandException("DOCKER_RUNTIME_UNAVAILABLE",
                        "Configured Docker executable must not traverse links or reparse points");
            }
        } catch (IOException error) {
            throw new SandboxCommandException("DOCKER_RUNTIME_UNAVAILABLE",
                    "Configured Docker executable cannot be resolved", error);
        }
        String fileName = normalized.getFileName() == null
                ? "" : normalized.getFileName().toString();
        if (!(fileName.equals("docker") || fileName.equalsIgnoreCase("docker.exe"))) {
            throw new SandboxCommandException("DOCKER_RUNTIME_UNAVAILABLE",
                    "Configured sandbox runtime must be the Docker CLI executable");
        }
        Object identityBefore = requireStableIdentity(normalized, "Docker executable");
        verifyExecutableDigest(normalized);
        Object identityAfter = requireStableIdentity(normalized, "Docker executable");
        if (!identityBefore.equals(identityAfter)) {
            throw new SandboxCommandException("DOCKER_RUNTIME_UNTRUSTED",
                    "Configured Docker executable changed identity while it was verified");
        }
        requireUsableDockerConfigDirectory();
        if (!localDockerHost().equals(dockerHost)) {
            throw new SandboxCommandException("DOCKER_RUNTIME_UNAVAILABLE",
                    "Configured Docker endpoint is not the platform-local engine");
        }
        return normalized;
    }

    private void verifyExecutableDigest(Path executable) {
        try (var input = Files.newInputStream(executable)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!MessageDigest.isEqual(
                    actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    dockerExecutableSha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                throw new SandboxCommandException("DOCKER_RUNTIME_UNTRUSTED",
                        "Configured Docker executable did not match its approved SHA-256");
            }
        } catch (IOException error) {
            throw new SandboxCommandException("DOCKER_RUNTIME_UNAVAILABLE",
                    "Configured Docker executable could not be hashed", error);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** Require an existing, absolute, link-free and empty CLI config directory. */
    public Path requireUsableDockerConfigDirectory() {
        final Path path;
        try {
            path = Path.of(dockerConfigDirectory);
        } catch (InvalidPathException error) {
            throw new SandboxCommandException("DOCKER_RUNTIME_UNAVAILABLE",
                    "Configured Docker config directory is invalid", error);
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!path.isAbsolute()
                || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalized)) {
            throw new SandboxCommandException("DOCKER_RUNTIME_UNAVAILABLE",
                    "Configured Docker config must be an absolute regular directory");
        }
        try {
            if (!normalized.toRealPath().equals(normalized)) {
                throw new SandboxCommandException("DOCKER_RUNTIME_UNAVAILABLE",
                        "Configured Docker config must not traverse links or reparse points");
            }
            try (var entries = Files.list(normalized)) {
                if (entries.findAny().isPresent()) {
                    throw new SandboxCommandException("DOCKER_RUNTIME_UNAVAILABLE",
                            "Configured Docker config directory must remain empty");
                }
            }
        } catch (IOException error) {
            throw new SandboxCommandException("DOCKER_RUNTIME_UNAVAILABLE",
                    "Configured Docker config directory cannot be verified", error);
        }
        return normalized;
    }

    private static Object requireStableIdentity(Path path, String field) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Object fileKey = attributes.fileKey();
            if (fileKey != null) {
                return fileKey;
            }
            if (!isWindows()) {
                throw new SandboxCommandException("DOCKER_RUNTIME_UNTRUSTED",
                        field + " filesystem does not expose a stable identity");
            }
            return new WindowsFileIdentity(attributes.creationTime().toMillis(),
                    attributes.lastModifiedTime().toMillis(), attributes.size(),
                    attributes.isDirectory(), attributes.isRegularFile());
        } catch (IOException error) {
            throw new SandboxCommandException("DOCKER_RUNTIME_UNAVAILABLE",
                    field + " identity could not be verified", error);
        }
    }

    private record WindowsFileIdentity(long createdMillis, long modifiedMillis, long size,
                                       boolean directory, boolean regularFile) {
    }

    public List<String> dockerCommandPrefix() {
        return List.of(requireUsableDockerExecutable().toString(),
                "--config", requireUsableDockerConfigDirectory().toString(),
                "--host", dockerHost);
    }

    public String cpuLimit() {
        return String.format(Locale.ROOT, "%.3f", cpuMilliCores / 1000.0);
    }

    public static void rejectControlOrComma(String value, String field) {
        if (value.indexOf(',') >= 0) {
            throw new IllegalArgumentException(field + " must not contain a comma");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(field + " must not contain control characters");
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
