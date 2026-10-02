package com.achintha.orderservice.client;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * store-service's internal endpoints ({@code ROLE_SERVICE}), resolved through Eureka. Call it through
 * {@link StoreServiceGateway}.
 */
@FeignClient(name = "store-service", contextId = "storeServiceClient")
public interface StoreServiceClient {

    @GetMapping("/internal/stores/{id}")
    StoreInfo store(@PathVariable("id") UUID storeId, @RequestHeader(HttpHeaders.AUTHORIZATION) String bearer);

    @GetMapping("/internal/stores/by-public-id/{publicId}")
    StoreInfo storeByPublicId(@PathVariable("publicId") String publicId,
                              @RequestHeader(HttpHeaders.AUTHORIZATION) String bearer);

    /** Active accounts with full account numbers: shown only to the order's own customer, never logged. */
    @GetMapping("/internal/stores/{id}/bank-accounts")
    List<BankAccount> bankAccounts(@PathVariable("id") UUID storeId,
                                   @RequestHeader(HttpHeaders.AUTHORIZATION) String bearer);

    @GetMapping("/internal/stores/{id}/couriers")
    List<StoreCourier> couriers(@PathVariable("id") UUID storeId,
                                @RequestHeader(HttpHeaders.AUTHORIZATION) String bearer);

    @GetMapping("/internal/settings")
    List<Setting> settings(@RequestHeader(HttpHeaders.AUTHORIZATION) String bearer);

    @GetMapping("/internal/holidays")
    List<Holiday> holidays(@RequestParam("from") LocalDate from, @RequestParam("to") LocalDate to,
                           @RequestHeader(HttpHeaders.AUTHORIZATION) String bearer);

    /** @param visible {@code published && acceptingOrders}: the store takes new orders right now */
    record StoreInfo(UUID id, String publicId, String name, String slug, boolean published, boolean acceptingOrders,
                     boolean visible, boolean codEnabled) {
    }

    record BankAccount(UUID id, String publicId, String bankCode, String bankName, String branch,
                       String accountName, String accountNumber, String accountNumberMasked) {

        @Override
        public String toString() {
            return "BankAccount[publicId=" + publicId + ", accountNumber=" + accountNumberMasked + "]";
        }
    }

    /** @param trackingUrlTemplate an https URL containing {@code {trackingNumber}} */
    record StoreCourier(String code, String name, String trackingUrlTemplate, String trackingNumberRegex,
                        boolean codSupported, boolean active, Instant enabledAt) {
    }

    record Setting(String key, String value, String type) {
    }

    record Holiday(LocalDate date, String name, String type) {
    }
}
