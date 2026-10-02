package com.achintha.orderservice.support;

import org.testcontainers.postgresql.PostgreSQLContainer;

/** One PostgreSQL container for the whole test run (started on first use, stopped by Testcontainers at JVM exit). */
public final class SharedPostgres {

    public static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer("postgres:17-alpine");

    static {
        CONTAINER.start();
    }

    private SharedPostgres() {
    }
}
