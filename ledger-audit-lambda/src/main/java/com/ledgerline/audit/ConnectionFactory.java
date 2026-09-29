package com.ledgerline.audit;

import java.sql.Connection;
import java.sql.SQLException;

/** Opens a new JDBC connection. Lets tests hand the auditor a Testcontainers database. */
@FunctionalInterface
public interface ConnectionFactory {
    Connection open() throws SQLException;
}
