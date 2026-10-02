package com.achintha.orderservice.config;

import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start outside the {@code local} profile when the service client secret is blank, unresolved or still
 * the committed {@code LOCAL-DEV-ONLY} placeholder (config-server README, secrets policy).
 */
@Slf4j
@Component
public class InternalClientSecretGuard implements InitializingBean {

    static final String PLACEHOLDER_PREFIX = "LOCAL-DEV-ONLY";

    private final InternalAuthProperties properties;
    private final Environment environment;

    public InternalClientSecretGuard(InternalAuthProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        String secret = properties.clientSecret();
        boolean unusable = secret == null || secret.isBlank() || secret.contains("${");
        boolean local = Arrays.asList(environment.getActiveProfiles()).contains("local");
        if (local) {
            if (unusable) {
                log.warn("No service client secret (SERVICE_CLIENT_SECRET_ORDER): calls to user-service's internal "
                        + "endpoints will fail");
            }
            return;
        }
        if (unusable || secret.startsWith(PLACEHOLDER_PREFIX)) {
            throw new IllegalStateException("Service client '" + properties.clientId()
                    + "' has no usable secret outside the local profile; set SERVICE_CLIENT_SECRET_ORDER");
        }
    }
}
