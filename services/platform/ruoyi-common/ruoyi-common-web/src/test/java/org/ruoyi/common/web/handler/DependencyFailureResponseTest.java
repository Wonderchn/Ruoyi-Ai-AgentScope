package org.ruoyi.common.web.handler;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.assertj.core.api.Assertions.*;

@Tag("dev")
class DependencyFailureResponseTest {
    @Test void databaseFailureReturnsHttp503AndNeverEchoesTheSqlOrCredential() {
        var result = new GlobalExceptionHandler().handleDependencyUnavailable(
                new DataAccessResourceFailureException("SELECT secret_canary FROM private_table"));
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(result.getBody()).containsEntry("code", 503);
        assertThat(result.getBody().toString()).contains("DEPENDENCY_UNAVAILABLE").doesNotContain("secret_canary", "private_table");
    }
}
