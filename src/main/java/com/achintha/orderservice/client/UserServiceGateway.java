package com.achintha.orderservice.client;

import com.achintha.orderservice.client.UserServiceClient.AddressResponse;
import com.achintha.orderservice.client.UserServiceClient.ProfileResponse;
import com.achintha.orderservice.client.UserServiceClient.SecurityStateResponse;
import com.achintha.orderservice.client.UserServiceClient.ServiceTokenRequest;
import com.achintha.orderservice.client.UserServiceClient.ServiceTokenResponse;
import com.achintha.orderservice.exception.ErrorCode;
import com.achintha.orderservice.exception.NotFoundException;
import feign.FeignException;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Resilient access to user-service ({@link RemoteCalls}, instance {@code userService}). A 404 passes through as
 * {@link NotFoundException}; anything else ends in a 503.
 */
@Component
public class UserServiceGateway {

    public static final String INSTANCE = "userService";
    private static final String SERVICE = "User service";

    private final UserServiceClient client;
    private final RemoteCalls remote;

    public UserServiceGateway(UserServiceClient client, RemoteCalls remote) {
        this.client = client;
        this.remote = remote;
    }

    public ServiceTokenResponse serviceToken(String clientId, String clientSecret) {
        return remote.call(INSTANCE, SERVICE, "obtain a service token",
                () -> client.serviceToken(new ServiceTokenRequest(clientId, clientSecret)));
    }

    /**
     * @param tokenSupplier gives the current service token; asked again on every attempt, so a retry after a 401
     *                      (expired token, invalidated by {@code onUnauthorized}) uses a fresh one
     */
    public SecurityStateResponse securityState(UUID userId, Supplier<String> tokenSupplier, Runnable onUnauthorized) {
        return remote.call(INSTANCE, SERVICE, "read the security state of a user", () -> {
            try {
                return client.securityState(userId, "Bearer " + tokenSupplier.get());
            } catch (FeignException.NotFound e) {
                throw new NotFoundException("User not found");
            } catch (FeignException.Unauthorized e) {
                onUnauthorized.run();
                throw e;
            }
        });
    }

    /** The customer's own address, read with the customer's token. 404 {@code ADDRESS_NOT_FOUND} if not theirs. */
    public AddressResponse myAddress(String addressPublicId, String userToken) {
        return remote.call(INSTANCE, SERVICE, "load the delivery address", () -> {
            try {
                return client.myAddress(addressPublicId, "Bearer " + userToken);
            } catch (FeignException.NotFound e) {
                throw new NotFoundException(ErrorCode.ADDRESS_NOT_FOUND, "Address not found");
            }
        });
    }

    /** The customer's own profile, read with the customer's token. */
    public ProfileResponse me(String userToken) {
        return remote.call(INSTANCE, SERVICE, "load the customer's contact details", () -> client.me("Bearer " + userToken));
    }
}
