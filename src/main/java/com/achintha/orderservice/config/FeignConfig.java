package com.achintha.orderservice.config;

import com.achintha.orderservice.client.UserServiceClient;
import com.achintha.orderservice.product.ProductClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.clientconfig.HttpClient5FeignConfiguration.HttpClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Here rather than on the application class, so web slice tests (@WebMvcTest) don't try to build Feign clients
@Configuration
@EnableFeignClients(basePackageClasses = {UserServiceClient.class, ProductClient.class})
public class FeignConfig {

    /**
     * Apache HttpClient 5 silently retries some I/O failures on its own, which would multiply the attempts set in
     * {@code resilience4j.retry}. Resilience4j is the only retry layer.
     */
    @Bean
    HttpClientBuilderCustomizer disableHttpClientRetries() {
        return builder -> builder.disableAutomaticRetries();
    }
}
