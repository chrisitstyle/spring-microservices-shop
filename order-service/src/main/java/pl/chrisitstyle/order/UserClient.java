package pl.chrisitstyle.order;

import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import pl.chrisitstyle.order.exception.ExternalServiceException;
import pl.chrisitstyle.order.exception.OrderCreationException;

@Component
public class UserClient {

    private final RestClient restClient;

    public UserClient(
            RestClient.Builder builder,
            @Value("${services.user.url}") String userServiceUrl
    ) {
        this.restClient = builder
                .baseUrl(userServiceUrl)
                .build();
    }

    @Retry(name = "userService")
    public UserResponse getUser(Long userId) {
        try {
            return restClient.get()
                    .uri("/users/{id}", userId)
                    .retrieve()
                    .body(UserResponse.class);

        } catch (RestClientResponseException exception) {

            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new OrderCreationException(
                        "User " + userId + " not found"
                );
            }

            throw new ExternalServiceException(
                    "User service returned error: "
                            + exception.getStatusCode(),
                    exception
            );

        } catch (ResourceAccessException exception) {
            throw new ExternalServiceException(
                    "User service unavailable",
                    exception
            );

        } catch (RestClientException exception) {
            throw new ExternalServiceException(
                    "User service communication failed",
                    exception
            );
        }
    }
}