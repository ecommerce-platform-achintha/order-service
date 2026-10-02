package com.achintha.orderservice.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Background jobs: the outbox relay, the order deadline scheduler and the idempotency-key cleanup. ShedLock (JDBC, database time) makes sure only
 * one replica runs each job at a time. {@code app.scheduling.enabled=false} turns the jobs off (tests call them
 * directly).
 */
@Configuration
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class SchedulingConfig {

    @Configuration
    @ConditionalOnProperty(name = "app.scheduling.shedlock.enabled", havingValue = "true", matchIfMissing = true)
    @EnableSchedulerLock(defaultLockAtMostFor = "${app.scheduling.shedlock.default-lock-at-most-for:PT10M}")
    static class ShedLockConfig {

        @Bean
        LockProvider lockProvider(DataSource dataSource) {
            return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                    .withJdbcTemplate(new JdbcTemplate(dataSource))
                    .withTableName("shedlock")
                    .usingDbTime()
                    .build());
        }
    }
}
