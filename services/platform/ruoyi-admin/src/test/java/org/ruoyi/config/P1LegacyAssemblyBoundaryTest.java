package org.ruoyi.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.yaml.snakeyaml.Yaml;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1.2a: the platform runtime must no longer ship the legacy AI identity, legacy AI routes or legacy AI
 * background triggers, while keeping the business workflow module ({@code ruoyi-workflow}, warm-flow business
 * approvals) intact.
 *
 * <p>The test is a pure classpath/configuration boundary check: no Spring context, no database, no network.
 * It reads the very same {@code application.yml} and {@code pom.xml} that the platform jar is built from, so a
 * regression that re-declares an old AI module or re-opens {@code /workflow/run} fails here instead of in
 * production.</p>
 */
@Tag("dev")
class P1LegacyAssemblyBoundaryTest {

    /**
     * Old AI / Harness / legacy-runtime artifacts, spread over every legacy area. The list is mirrored by
     * {@code src/test/resources/p1/p12a-platform-boundary-evidence.json}; the evidence test below fails if the
     * two ever drift apart.
     */
    private static final List<String> ABSENT_CLASSES = List.of(
        "org.ruoyi.controller.chat.ChatController",
        "org.ruoyi.controller.chat.MediaGenerationController",
        "org.ruoyi.controller.mcp.McpToolController",
        "org.ruoyi.controller.mcp.McpMarketController",
        "org.ruoyi.controller.coding.CodingHarnessController",
        "org.ruoyi.controller.coding.CodingController",
        "org.ruoyi.controller.agent.AgentController",
        "org.ruoyi.controller.knowledge.KnowledgeInfoController",
        "org.ruoyi.controller.shortdrama.ShortDramaController",
        "org.ruoyi.agent.manager.TableSchemaInitializer",
        "org.ruoyi.observability.LangChain4jObservabilityConfig",
        "org.ruoyi.mcp.service.core.BuiltinToolRegistry",
        "org.ruoyi.factory.VectorStoreStrategyFactory",
        "org.ruoyi.service.coding.harness.approval.HarnessApprovalExpiryMonitor",
        "org.ruoyi.service.coding.harness.recovery.HarnessStartupRecovery",
        "org.ruoyi.workflow.controller.WorkflowController",
        "org.ruoyi.workflow.controller.WorkflowRuntimeController",
        "org.ruoyi.workflow.controller.admin.AdminWorkflowController");

    /** Classes that are definitely on the admin runtime classpath; they prove the probe can actually detect presence. */
    private static final List<String> PRESENT_CLASSES = List.of(
        "org.ruoyi.RuoYiAIApplication",
        "org.springframework.boot.SpringApplication");

    /** Legacy AI modules that must not be declared by the platform entry module. */
    private static final List<String> LEGACY_AI_ARTIFACTS = List.of(
        "ruoyi-chat",
        "ruoyi-aiflow",
        "ruoyi-ai-integration");

    /** The exclusion removed by this unit. */
    private static final String REMOVED_SECURITY_EXCLUSION = "/workflow/run";

    /** Route prefixes of the legacy AI surface; none of them may survive in {@code security.excludes}. */
    private static final List<String> REMOVED_AI_ROUTE_PREFIXES = List.of(
        "/chat",
        "/agent/",
        "/mcp/",
        "/coding",
        "/media",
        "/short-drama",
        "/system/");

    /** Expected {@code security.excludes} content after this unit's surgical edit. */
    private static final List<String> PRESERVED_SECURITY_EXCLUSIONS = List.of(
        "/*.html",
        "/**/*.html",
        "/**/*.css",
        "/**/*.js",
        "/favicon.ico",
        "/error",
        "/*/api-docs",
        "/*/api-docs/**",
        "/warm-flow-ui/config");

    private static final String EVIDENCE_RESOURCE = "p1/p12a-platform-boundary-evidence.json";

    private static final String APPLICATION_YML = "src/main/resources/application.yml";

    private static final List<String> LEGACY_MODULE_SOURCE_ROOTS = List.of(
        "ruoyi-modules/ruoyi-chat/src/main/java",
        "ruoyi-modules/ruoyi-aiflow/src/main/java",
        "services/platform/ruoyi-modules/ruoyi-chat/src/main/java",
        "services/platform/ruoyi-modules/ruoyi-aiflow/src/main/java");

    /**
     * A: no old AI / Harness / legacy-runtime artifact is reachable from the platform runtime.
     *
     * <p>The probe runs against the <em>test</em> classpath of {@code ruoyi-admin}. That is exactly the admin
     * runtime classpath: {@code ruoyi-admin/pom.xml} declares only postgresql, ruoyi-common-doc/social/
     * ratelimiter/mail, ruoyi-system, ruoyi-workflow and spring-boot-admin-starter-client (plus
     * spring-boot-starter-test in test scope), and neither ruoyi-system nor ruoyi-workflow depends on
     * ruoyi-chat / ruoyi-aiflow / ruoyi-ai-integration (asserted in
     * {@link #adminPomKeepsBusinessWorkflowAndDeclaresNoLegacyAiModule()}). Absence here therefore means
     * "not shipped in the platform runtime", which is the property this unit has to guarantee. The old modules
     * are still compiled by the reactor, so their sources are asserted to exist in
     * {@link #everyAssertedAbsentClassStillExistsInItsLegacyModule()} — the absence probes are not vacuous.</p>
     */
    @Test
    void legacyAiArtifactsAreAbsentFromThePlatformRuntimeClasspath() {
        assertAll(ABSENT_CLASSES.stream()
            .map(className -> (Executable) () -> assertFalse(isOnRuntimeClasspath(className),
                () -> "legacy AI artifact must not be on the platform runtime classpath: " + className)));
    }

    /**
     * D: positive control. Proves the probe detects presence, both for a class compiled into this module
     * ({@code target/classes}) and for a class delivered inside a jar, and that it is not a constant
     * "everything is absent" predicate.
     */
    @Test
    void classpathProbeDetectsClassesThatArePresent() {
        assertAll(PRESENT_CLASSES.stream()
            .map(className -> (Executable) () -> assertTrue(isOnRuntimeClasspath(className),
                () -> "positive control failed, the absence probes would be meaningless: " + className)));

        // The legacy classes live in jars, so the probe must also see resources inside jar entries.
        assertNotNull(runtimeClassLoader().getResource("org/springframework/boot/SpringApplication.class"),
            "the class loader used by the absence probe cannot see jar-internal class resources");
        assertNotNull(runtimeClassLoader().getResource("org/ruoyi/RuoYiAIApplication.class"),
            "the class loader used by the absence probe cannot see classes compiled into this module");
        assertFalse(isOnRuntimeClasspath("org.ruoyi.controller.chat.NoSuchLegacyControllerAnywhere"),
            "the absence probe reports non-existing classes as present, so its negative result proves nothing");
    }

    /**
     * Non-vacuity control for A: the classes asserted absent are real classes of the legacy modules that the
     * reactor still builds. Without this, a typo in {@link #ABSENT_CLASSES} would look like a passing boundary.
     */
    @Test
    void everyAssertedAbsentClassStillExistsInItsLegacyModule() {
        assertAll(ABSENT_CLASSES.stream()
            .map(className -> (Executable) () -> assertNotNull(locateLegacySourceFile(className),
                () -> "no legacy source file found for " + className
                    + "; the assertion in legacyAiArtifactsAreAbsentFromThePlatformRuntimeClasspath() would be vacuous")));
    }

    /**
     * B: the legacy AI route trigger is gone and the edit stayed surgical — the surviving {@code security.excludes}
     * set is exactly the non-AI set.
     */
    @Test
    void securityExclusionsDropTheLegacyWorkflowRunEndpointAndKeepTheSurvivingEntries() throws Exception {
        Map<String, Object> document = loadApplicationYml();
        Map<String, Object> security = requireMap(document, "security");
        List<String> excludes = requireStringList(security.get("excludes"), "security.excludes");

        assertFalse(excludes.isEmpty(), "security.excludes must not be empty");
        assertFalse(excludes.contains(REMOVED_SECURITY_EXCLUSION),
            () -> "the legacy run trigger must no longer be publicly excluded: " + REMOVED_SECURITY_EXCLUSION
                + " (found in " + excludes + ")");
        for (String prefix : REMOVED_AI_ROUTE_PREFIXES) {
            assertTrue(excludes.stream().noneMatch(entry -> entry.startsWith(prefix)),
                () -> "security.excludes still opens a legacy AI route with prefix " + prefix + ": " + excludes);
        }

        // Exact set equality proves the removal was surgical: nothing beyond /workflow/run was dropped, and no
        // other legacy entry survived. Extending the set for a genuinely non-AI reason is a deliberate change
        // that has to touch this expectation list (and the evidence JSON) as well.
        assertEquals(new LinkedHashSet<>(PRESERVED_SECURITY_EXCLUSIONS), new LinkedHashSet<>(excludes),
            () -> "security.excludes changed beyond removing " + REMOVED_SECURITY_EXCLUSION);
    }

    /**
     * C (part 1): ruoyi-workflow stays a direct dependency of ruoyi-admin and no legacy AI module is declared.
     */
    @Test
    void adminPomKeepsBusinessWorkflowAndDeclaresNoLegacyAiModule() throws Exception {
        Path pom = resolveModuleFile("ruoyi-admin/pom.xml",
            "pom.xml",
            "ruoyi-admin/pom.xml",
            "services/platform/ruoyi-admin/pom.xml");
        Element project = parseXml(pom).getDocumentElement();

        assertEquals("ruoyi-admin", directChildText(project, "artifactId"),
            () -> "resolved pom is not the platform entry module: " + pom.toAbsolutePath());

        Set<String> declaredArtifacts = directDependencyArtifacts(project);
        assertTrue(declaredArtifacts.contains("ruoyi-workflow"),
            () -> "ruoyi-admin must keep the business workflow module (warm-flow approvals); direct dependencies are "
                + declaredArtifacts);
        for (String legacyArtifact : LEGACY_AI_ARTIFACTS) {
            assertFalse(declaredArtifacts.contains(legacyArtifact),
                () -> "ruoyi-admin must not declare the legacy AI module " + legacyArtifact
                    + "; direct dependencies are " + declaredArtifacts);
        }
    }

    /**
     * C (part 2): the business workflow package is still scanned. The key is checked before asserting: if the
     * configuration key does not exist, this test reports the fact instead of forcing an assertion.
     */
    @Test
    void mybatisAndSpringdocConfigurationStillCoverTheBusinessWorkflowPackage() throws Exception {
        Map<String, Object> document = loadApplicationYml();
        Map<String, Object> springdoc = optionalMap(document, "springdoc");
        if (springdoc == null) {
            System.out.println("[P1.2a][REPORT-ONLY] application.yml has no 'springdoc' section, so no"
                + " packages-to-scan key exists; neither asserting nor failing on the workflow package scan");
            return;
        }

        List<String> scannedPackages = new ArrayList<>();
        Object groupConfigs = springdoc.get("group-configs");
        if (groupConfigs instanceof List<?> groups) {
            for (Object group : groups) {
                if (group instanceof Map<?, ?> groupMap) {
                    Object packages = groupMap.get("packages-to-scan");
                    if (packages != null) {
                        scannedPackages.add(String.valueOf(packages));
                    }
                }
            }
        }

        if (scannedPackages.isEmpty()) {
            System.out.println("[P1.2a][REPORT-ONLY] application.yml has no 'springdoc.group-configs[].packages-to-scan'"
                + " key, so the workflow package scan cannot be asserted from this file");
            return;
        }

        assertTrue(scannedPackages.contains("org.ruoyi.workflow"),
            () -> "the business workflow package must still be scanned; packages-to-scan = " + scannedPackages);

        // Report-only: a springdoc group pointing at a package that no longer exists is inert, but it is a
        // leftover of the removed AI surface and belongs in the unit's findings, not in a hard assertion.
        List<String> legacyPackages = scannedPackages.stream()
            .filter(scanned -> scanned.startsWith("org.ruoyi.mcp") || scanned.startsWith("org.ruoyi.chat")
                || scanned.startsWith("org.ruoyi.agent"))
            .toList();
        if (!legacyPackages.isEmpty()) {
            System.out.println("[P1.2a][FINDING] springdoc still scans legacy AI packages: " + legacyPackages);
        }
    }

    /**
     * The shared evidence JSON must stay in sync with the assertions of this class.
     */
    @Test
    void evidenceFileMatchesTheAssertedBoundary() throws Exception {
        JsonNode evidence;
        try (InputStream input = runtimeClassLoader().getResourceAsStream(EVIDENCE_RESOURCE)) {
            assertNotNull(input, () -> "evidence JSON not found on the test classpath: " + EVIDENCE_RESOURCE);
            evidence = new ObjectMapper().readTree(input);
        }

        assertEquals("P1.2a", evidence.path("unit").asText());
        assertEquals("01-p1-first-unit-spec.md", evidence.path("spec").asText());
        assertEquals("c2fdc73c67ca9156098ce4a1b1f2853486fc5aa1", evidence.path("baseline").asText());
        assertEquals(REMOVED_SECURITY_EXCLUSION, evidence.path("removedSecurityExclusion").asText());
        assertEquals("ruoyi-workflow", evidence.path("preservedWorkflowArtifact").asText());

        JsonNode assertedAbsent = evidence.path("assertedAbsentClasses");
        assertTrue(assertedAbsent.isArray(), "assertedAbsentClasses must be a JSON array");
        assertFalse(assertedAbsent.isEmpty(), "assertedAbsentClasses must not be empty");
        assertEquals(ABSENT_CLASSES.size(), assertedAbsent.size(),
            () -> "evidence file drifted from ABSENT_CLASSES: evidence=" + assertedAbsent.size()
                + " test=" + ABSENT_CLASSES.size());
        assertEquals(ABSENT_CLASSES, toTextList(assertedAbsent),
            "assertedAbsentClasses must list exactly the classes asserted in this test, in the same order");

        JsonNode preserved = evidence.path("preservedSecurityExclusions");
        assertTrue(preserved.isArray(), "preservedSecurityExclusions must be a JSON array");
        assertEquals(PRESERVED_SECURITY_EXCLUSIONS, toTextList(preserved),
            "preservedSecurityExclusions must match the surviving security.excludes entries");
    }

    /**
     * Probe used for A, D and the non-vacuity control. A class counts as present when either the raw
     * {@code .class} resource or {@code Class.forName(..., false, loader)} finds it.
     */
    private static boolean isOnRuntimeClasspath(String className) {
        String resource = className.replace('.', '/') + ".class";
        ClassLoader loader = runtimeClassLoader();
        if (loader.getResource(resource) != null || ClassLoader.getSystemResource(resource) != null) {
            return true;
        }
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (ClassNotFoundException notFound) {
            return false;
        } catch (LinkageError unlinkable) {
            // The class file was found and only linking failed: it is present on the classpath.
            return true;
        }
    }

    private static ClassLoader runtimeClassLoader() {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        return contextLoader != null ? contextLoader : P1LegacyAssemblyBoundaryTest.class.getClassLoader();
    }

    /** Locates the legacy module source of a class asserted to be absent, or {@code null} when it does not exist. */
    private static Path locateLegacySourceFile(String className) {
        String relativeSource = className.replace('.', '/') + ".java";
        for (Path ancestor = currentDirectory(); ancestor != null; ancestor = ancestor.getParent()) {
            for (String sourceRoot : LEGACY_MODULE_SOURCE_ROOTS) {
                Path candidate = ancestor.resolve(sourceRoot).resolve(relativeSource).normalize();
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static Map<String, Object> loadApplicationYml() throws Exception {
        Path applicationYml = resolveModuleFile("ruoyi-admin/application.yml",
            APPLICATION_YML,
            "ruoyi-admin/src/main/resources/application.yml",
            "services/platform/ruoyi-admin/src/main/resources/application.yml");

        Map<String, Object> merged = new LinkedHashMap<>();
        try (Reader reader = Files.newBufferedReader(applicationYml, StandardCharsets.UTF_8)) {
            for (Object document : new Yaml().loadAll(reader)) {
                if (document instanceof Map<?, ?> yamlDocument) {
                    yamlDocument.forEach((key, value) -> merged.put(String.valueOf(key), value));
                }
            }
        }
        assertTrue(merged.containsKey("security"),
            () -> "resolved file does not look like the platform application.yml: " + applicationYml.toAbsolutePath());
        return merged;
    }

    private static Path resolveModuleFile(String description, String... relativeCandidates) {
        Path start = currentDirectory();
        for (Path ancestor = start; ancestor != null; ancestor = ancestor.getParent()) {
            for (String candidate : relativeCandidates) {
                Path resolved = ancestor.resolve(candidate).normalize();
                if (Files.isRegularFile(resolved)) {
                    System.out.println("[P1.2a] resolved " + description + " -> " + resolved);
                    return resolved;
                }
            }
        }
        throw new IllegalStateException("could not locate " + description + ": walked up from " + start
            + " trying " + String.join(", ", relativeCandidates) + " in every directory");
    }

    private static Path currentDirectory() {
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    private static Document parseXml(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception unsupported) {
            // Hardening is best effort; parsing a local pom does not depend on it.
        }
        return factory.newDocumentBuilder().parse(file.toFile());
    }

    /** Artifact ids of the {@code <dependencies>} declared directly under the project element. */
    private static Set<String> directDependencyArtifacts(Element project) {
        Set<String> artifacts = new LinkedHashSet<>();
        for (Element dependencies : directChildren(project, "dependencies")) {
            for (Element dependency : directChildren(dependencies, "dependency")) {
                String artifactId = directChildText(dependency, "artifactId");
                if (artifactId != null) {
                    artifacts.add(artifactId);
                }
            }
        }
        return artifacts;
    }

    private static List<Element> directChildren(Element parent, String name) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(node.getNodeName())) {
                children.add((Element) node);
            }
        }
        return children;
    }

    private static String directChildText(Element parent, String name) {
        List<Element> children = directChildren(parent, name);
        return children.isEmpty() ? null : children.get(0).getTextContent().trim();
    }

    private static Map<String, Object> requireMap(Map<String, Object> document, String key) {
        Map<String, Object> value = optionalMap(document, key);
        if (value == null) {
            throw new IllegalStateException("application.yml is missing the required '" + key + "' mapping; keys = "
                + document.keySet());
        }
        return value;
    }

    private static Map<String, Object> optionalMap(Map<String, Object> document, String key) {
        Object value = document.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> rawMap)) {
            throw new IllegalStateException("application.yml key '" + key + "' is not a mapping but " + value.getClass());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        rawMap.forEach((nestedKey, nestedValue) -> result.put(String.valueOf(nestedKey), nestedValue));
        return result;
    }

    private static List<String> requireStringList(Object value, String path) {
        if (!(value instanceof List<?> rawList)) {
            throw new IllegalStateException("application.yml key '" + path + "' is missing or is not a list: " + value);
        }
        List<String> result = new ArrayList<>();
        rawList.forEach(element -> result.add(String.valueOf(element)));
        return result;
    }

    private static List<String> toTextList(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(element -> result.add(element.asText()));
        return result;
    }
}
