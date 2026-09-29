package com.ledgerline.audit;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * A new connection per invocation, no pool: the audit runs once a night for a few seconds.
 * sslmode=require because RDS for PostgreSQL 16 forces TLS (rds.force_ssl=1). It encrypts but does
 * not verify the server certificate; verify-full would need the RDS CA bundle in the jar.
 */
final class JdbcConnectionFactory implements ConnectionFactory {

    private final String url;
    private final String user;
    private final Supplier<String> password;

    JdbcConnectionFactory(AuditConfig config, Supplier<String> password) {
        this.url = "jdbc:postgresql://%s:%d/%s".formatted(config.dbHost(), config.dbPort(), config.dbName());
        this.user = config.dbUser();
        this.password = password;
    }

    @Override
    public Connection open() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password.get());
        props.setProperty("sslmode", "require");
        props.setProperty("connectTimeout", "10"); // seconds; an SG/NAT problem fails fast instead of hanging
        props.setProperty("socketTimeout", "45");  // below the 60 s Lambda timeout, so we log a real error
        props.setProperty("ApplicationName", "ledger-audit-lambda");
        return DriverManager.getConnection(url, props);
    }
}
