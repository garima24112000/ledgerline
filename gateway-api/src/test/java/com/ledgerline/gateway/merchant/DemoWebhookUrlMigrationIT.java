package com.ledgerline.gateway.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * V6 sets the seeded demo merchants' webhook URL from the demo_webhook_url placeholder. It runs Flyway
 * directly against its own fresh Postgres, because the shared IT database is already migrated with the
 * default (localhost) value.
 */
class DemoWebhookUrlMigrationIT {

    private static final String IN_CLUSTER_URL = "http://ledgerline-demo-merchant:8083/webhooks";

    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void start() {
        postgres.start();
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    @Test
    void seededMerchantsGetTheConfiguredUrlAndLaterChangesDoNotApply() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

        migrate(dataSource, IN_CLUSTER_URL);
        assertThat(seededUrls(dataSource)).containsOnly(IN_CLUSTER_URL).hasSize(3);

        // One-time seed configuration: V6 is already applied, so a new value changes nothing.
        migrate(dataSource, "http://somewhere-else:8083/webhooks");
        assertThat(seededUrls(dataSource)).containsOnly(IN_CLUSTER_URL);
    }

    private static void migrate(DriverManagerDataSource dataSource, String webhookUrl) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .placeholders(Map.of("demo_webhook_url", webhookUrl))
                .load()
                .migrate();
    }

    private static List<String> seededUrls(DriverManagerDataSource dataSource) {
        return JdbcClient.create(dataSource)
                .sql("SELECT webhook_url FROM merchants WHERE name IN ('Chai Point', 'Book Nook', 'Pixel Prints')")
                .query(String.class)
                .list();
    }
}
