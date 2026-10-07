package org.ruoyi.aiidentity;

import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import com.baomidou.dynamic.datasource.creator.hikaricp.HikariCpConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.*;
import org.springframework.core.io.ClassPathResource;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@Tag("dev")
class EmbeddedDatabaseBindingTest {
    @Test void embeddedOverridesTheLegacyProdDriverAndBindsBothTimeoutLayers() throws Exception {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("fixture", Map.of("PLATFORM_DB_PASSWORD", "fixture-placeholder")));
        var loader = new YamlPropertySourceLoader();
        for (var source : loader.load("embedded", new ClassPathResource("application-embedded.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        for (var source : loader.load("prod", new ClassPathResource("application-prod.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        var binder = Binder.get(environment);
        var master = binder.bind("spring.datasource.dynamic.datasource.master", DataSourceProperty.class).get();
        assertThat(master.getDriverClassName()).isEqualTo("org.postgresql.Driver");
        assertThat(master.getUrl()).startsWith("jdbc:postgresql:").contains("currentSchema=platform,extensions");
        assertThat(master.getHikari().getDataSourceProperties()).containsEntry("connectTimeout", "3").containsEntry("socketTimeout", "5");
        var pool = binder.bind("spring.datasource.dynamic.hikari", HikariCpConfig.class).get();
        assertThat(pool.getConnectionTimeout()).isEqualTo(3000L);
        assertThat(pool.getValidationTimeout()).isEqualTo(1000L);
    }
}
