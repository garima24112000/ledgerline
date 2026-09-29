package com.ledgerline.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditConfigTest {

    @Test
    void appliesDefaultsForOptionalSettings() {
        AuditConfig config = AuditConfig.fromEnvironment(Map.of(
                "DB_HOST", "db.example", "DB_PASSWORD_PARAM", "/ledgerline/db/password", "AUDIT_BUCKET", "bucket"));

        assertThat(config.dbPort()).isEqualTo(5432);
        assertThat(config.dbName()).isEqualTo("ledgerline");
        assertThat(config.zone()).isEqualTo(ZoneId.of("America/New_York"));
        assertThat(config.unknownMaxAge()).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    void failsFastWhenARequiredVariableIsMissing() {
        assertThatThrownBy(() -> AuditConfig.fromEnvironment(Map.of("DB_HOST", "db.example")))
                .hasMessageContaining("DB_PASSWORD_PARAM");
    }
}
