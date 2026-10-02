package com.achintha.orderservice.client;

import com.achintha.orderservice.client.StoreServiceClient.BankAccount;
import com.achintha.orderservice.client.StoreServiceClient.Holiday;
import com.achintha.orderservice.client.StoreServiceClient.Setting;
import com.achintha.orderservice.client.StoreServiceClient.StoreCourier;
import com.achintha.orderservice.client.StoreServiceClient.StoreInfo;
import com.achintha.orderservice.exception.ErrorCode;
import com.achintha.orderservice.exception.NotFoundException;
import com.achintha.orderservice.security.ServiceTokenProvider;
import feign.FeignException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Resilient access to store-service ({@link RemoteCalls}, instance {@code storeService}) with a service token
 * (re-fetched after a 401). A 404 passes through as {@link NotFoundException}; anything else ends in a 503.
 */
@Component
public class StoreServiceGateway {

    public static final String INSTANCE = "storeService";
    private static final String SERVICE = "Store service";

    private final StoreServiceClient client;
    private final RemoteCalls remote;
    private final ServiceTokenProvider serviceTokens;

    public StoreServiceGateway(StoreServiceClient client, RemoteCalls remote, ServiceTokenProvider serviceTokens) {
        this.client = client;
        this.remote = remote;
        this.serviceTokens = serviceTokens;
    }

    public StoreInfo store(UUID storeId) {
        return call("load the store", bearer -> client.store(storeId, bearer));
    }

    /** 404 {@code STORE_NOT_ACCEPTING_ORDERS} for an unknown store: to the customer it simply cannot take orders. */
    public StoreInfo storeByPublicId(String publicId) {
        return call("load the store", bearer -> client.storeByPublicId(publicId, bearer));
    }

    public List<BankAccount> bankAccounts(UUID storeId) {
        return call("load the store's bank accounts", bearer -> client.bankAccounts(storeId, bearer));
    }

    public List<StoreCourier> couriers(UUID storeId) {
        return call("load the store's couriers", bearer -> client.couriers(storeId, bearer));
    }

    public List<Setting> settings() {
        return call("load the platform settings", client::settings);
    }

    public List<Holiday> holidays(LocalDate from, LocalDate to) {
        return call("load the holiday calendar", bearer -> client.holidays(from, to, bearer));
    }

    private <T> T call(String action, Function<String, T> feignCall) {
        return remote.call(INSTANCE, SERVICE, action, () -> {
            try {
                return feignCall.apply("Bearer " + serviceTokens.token());
            } catch (FeignException.NotFound e) {
                throw new NotFoundException(ErrorCode.NOT_FOUND, "Store not found");
            } catch (FeignException.Unauthorized e) {
                serviceTokens.invalidate();
                throw e;
            }
        });
    }
}
