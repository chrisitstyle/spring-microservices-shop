package pl.chrisitstyle.order;

import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import pl.chrisitstyle.order.exception.ExternalServiceException;
import pl.chrisitstyle.order.exception.OrderCreationException;

import static org.springframework.security.oauth2.client.web.client.RequestAttributeClientRegistrationIdResolver.clientRegistrationId;
import static org.springframework.security.oauth2.client.web.client.RequestAttributePrincipalResolver.principal;

@Component
public class UserClient {

    private static final String CLIENT_REGISTRATION_ID = "order-service-client";
    private static final String PRINCIPAL_NAME = "order-service";

    private final RestClient restClient;

    public UserClient(
            RestClient.Builder builder,
            OAuth2ClientHttpRequestInterceptor oauth2ClientHttpRequestInterceptor,
            @Value("${services.user.url}") String userServiceUrl
    ) {
        this.restClient = builder
                .baseUrl(userServiceUrl)
                .requestInterceptor(oauth2ClientHttpRequestInterceptor)
                .build();
    }

    @Retry(name = "userService")
    public UserResponse getUser(Long userId) {
        try {
            return restClient.get()
                    .uri("/users/{id}", userId)
                    .attributes(clientRegistrationId(CLIENT_REGISTRATION_ID))
                    .attributes(principal(PRINCIPAL_NAME))
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

    @Retry(name = "userService")
    public UserResponse getUserByKeycloakSubject(String keycloakSubject) {
        try {
            return restClient.get()
                    .uri(uriBuilder ->
                            uriBuilder
                                    .path("/users/by-keycloak-subject")
                                    .queryParam("subject", keycloakSubject)
                                    .build()
                    )
                    .attributes(clientRegistrationId(CLIENT_REGISTRATION_ID))
                    .attributes(principal(PRINCIPAL_NAME))
                    .retrieve()
                    .body(UserResponse.class);

        } catch (RestClientResponseException exception) {

            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new OrderCreationException(
                        "Authenticated user is not linked to a shop user"
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