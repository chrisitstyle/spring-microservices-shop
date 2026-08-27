package pl.chrisitstyle.order;

import feign.FeignException;
import feign.RetryableException;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Component;
import pl.chrisitstyle.order.exception.ExternalServiceException;
import pl.chrisitstyle.order.exception.OrderCreationException;

@Component
public class UserClient {

    private final UserFeignClient userFeignClient;

    public UserClient(
            UserFeignClient userFeignClient
    ) {
        this.userFeignClient = userFeignClient;
    }

    @Retry(name = "userService")
    public UserResponse getUser(Long userId) {
        try {
            return userFeignClient.getUser(userId);

        } catch (RetryableException exception) {
            throw new ExternalServiceException(
                    "User service unavailable",
                    exception
            );

        } catch (FeignException exception) {

            if (exception.status() == 404) {
                throw new OrderCreationException(
                        "User " + userId + " not found"
                );
            }

            throw new ExternalServiceException(
                    "User service returned error: "
                            + exception.status(),
                    exception
            );
        }
    }

    @Retry(name = "userService")
    public UserResponse getUserByKeycloakSubject(
            String keycloakSubject
    ) {
        try {
            return userFeignClient.getUserByKeycloakSubject(
                    keycloakSubject
            );

        } catch (RetryableException exception) {
            throw new ExternalServiceException(
                    "User service unavailable",
                    exception
            );

        } catch (FeignException exception) {

            if (exception.status() == 404) {
                throw new OrderCreationException(
                        "Authenticated user is not linked to a shop user"
                );
            }

            throw new ExternalServiceException(
                    "User service returned error: "
                            + exception.status(),
                    exception
            );
        }
    }
}