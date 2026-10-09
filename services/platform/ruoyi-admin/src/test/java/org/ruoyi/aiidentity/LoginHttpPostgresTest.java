package org.ruoyi.aiidentity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Instance-level regression for the login listener's tenant-scoped sys_user update.
 * Run against an independently booted real application, PostgreSQL and Redis.
 * The two users must be dedicated synthetic fixtures: this test clears only their
 * login_date/login_ip, then proves the HTTP login writes them back in the correct tenant.
 * Enable with RAGENT_LOGIN_HTTP_TEST=true; all endpoint, fixture and JDBC settings
 * come from RAGENT_LOGIN_* environment variables. Credentials and tokens are never logged.
 * Missing instance prerequisites are reported as skipped, never as successful logins.
 */
@Tag("dev")
@EnabledIfEnvironmentVariable(named = "RAGENT_LOGIN_HTTP_TEST", matches = "true",
    disabledReason = "Requires a running real platform instance and dedicated PostgreSQL/Redis fixtures")
class LoginHttpPostgresTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @ParameterizedTest(name = "real login and tenant-scoped database write: fixture {0}")
    @ValueSource(strings = {"A", "B"})
    void correctCredentialsIssueTokenAndPersistLoginInTheAuthenticatedTenant(String fixture) throws Exception {
        String tenant = env("TENANT_" + fixture);
        String user = env("USER_" + fixture);
        assertThat(env("TENANT_A")).isNotEqualTo(env("TENANT_B"));
        try (var connection = DriverManager.getConnection(env("JDBC_URL"), env("DB_USER"), env("DB_PASSWORD"))) {
            try (var clear = connection.prepareStatement(
                "UPDATE sys_user SET login_date=NULL, login_ip=NULL WHERE tenant_id=? AND user_name=?")) {
                clear.setString(1, tenant);
                clear.setString(2, user);
                assertThat(clear.executeUpdate()).as("exactly one dedicated synthetic login fixture").isEqualTo(1);
            }
            JsonNode body = login(user, tenant, env("PASSWORD"));
            assertThat(body.path("code").isIntegralNumber()).as("integer response code").isTrue();
            assertThat(body.path("code").intValue()).as("correct credentials must actually log in").isEqualTo(200);
            assertThat(body.path("data").path("access_token").asText()).as("issued token").isNotBlank();
            try (var query = connection.prepareStatement(
                "SELECT tenant_id, login_date, login_ip FROM sys_user WHERE tenant_id=? AND user_name=?")) {
                query.setString(1, tenant);
                query.setString(2, user);
                try (var row = query.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1)).isEqualTo(tenant);
                    assertThat(row.getTimestamp(2)).as("listener UPDATE must not be silently skipped").isNotNull();
                    assertThat(row.getString(3)).as("actual login IP persisted").isNotBlank();
                    assertThat(row.next()).isFalse();
                }
            }
        }
    }

    @Test
    void incorrectCredentialsCannotIssueAToken() throws Exception {
        JsonNode body = login(env("USER_A"), env("TENANT_A"), env("PASSWORD") + "-intentionally-invalid");
        assertThat(body.path("code").isIntegralNumber()).isTrue();
        assertThat(body.path("code").intValue()).isNotEqualTo(200);
        assertThat(body.path("data").path("access_token").asText()).isEmpty();
    }

    private JsonNode login(String user, String tenant, String password) throws Exception {
        String requestBody = json.writeValueAsString(Map.of("clientId", env("CLIENT_ID"),
            "grantType", "password", "tenantId", tenant, "username", user, "password", password));
        var request = HttpRequest.newBuilder(URI.create(env("BASE") + "/auth/login"))
            .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        // HTTP 200 alone cannot establish success: platform errors may also use that status.
        assertThat(response.statusCode()).as("HTTP transport status").isBetween(200, 599);
        return json.readTree(response.body());
    }

    private static String env(String suffix) {
        String value = System.getenv("RAGENT_LOGIN_" + suffix);
        assertThat(value).as("required environment setting RAGENT_LOGIN_%s", suffix).isNotBlank();
        return value;
    }
}
