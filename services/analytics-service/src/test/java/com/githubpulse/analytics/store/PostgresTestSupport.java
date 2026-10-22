package com.githubpulse.analytics.store;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One embedded PostgreSQL per test class, migrated with the real Flyway scripts. */
final class PostgresTestSupport {

    final EmbeddedPostgres postgres;
    final DataSource dataSource;
    final JdbcTemplate jdbc;
    final TransactionTemplate tx;

    PostgresTestSupport() throws IOException {
        postgres = EmbeddedPostgres.start();
        dataSource = postgres.getPostgresDatabase();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    void reset() {
        jdbc.execute("TRUNCATE github_events, repositories, minute_aggregates, repo_minute_aggregates, "
                + "rising_repositories, processing_failures RESTART IDENTITY");
    }

    void close() throws IOException {
        postgres.close();
    }
}
