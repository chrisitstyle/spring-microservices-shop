package pl.chrisitstyle.e2e;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import pl.chrisitstyle.e2e.support.KeycloakE2ESupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticationE2ETest {

    private static final String GATEWAY_BASE_URL =
            System.getProperty(
                    "e2e.gateway.base-url",
                    "http://localhost:8085"
            );

    private static final String ADMIN_USERNAME =
            "e2e-admin";

    private static final String ADMIN_PASSWORD =
            "e2e-password";

    private static final KeycloakE2ESupport keycloak =
            new KeycloakE2ESupport();

    private final HttpClient httpClient =
            HttpClient.newBuilder()
                    .connectTimeout(
                            Duration.ofSeconds(5)
                    )
                    .build();

    @BeforeAll
    static void prepareIdentity()
            throws Exception {

        keycloak.ensureTestClient();

        keycloak.ensureRealmUser(
                ADMIN_USERNAME,
                ADMIN_PASSWORD,
                "ADMIN"
        );
    }

    @Test
    void shouldRejectProtectedEndpointWithoutAccessToken()
            throws Exception {

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        GATEWAY_BASE_URL
                                                + "/orders/me"
                                )
                        )
                        .timeout(
                                Duration.ofSeconds(5)
                        )
                        .GET()
                        .build();

        HttpResponse<String> response =
                httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

        assertThat(response.statusCode())
                .isEqualTo(401);
    }

    @Test
    void shouldAllowAdminToAccessOrdersThroughGateway()
            throws Exception {

        String accessToken =
                keycloak.getUserAccessToken(
                        ADMIN_USERNAME,
                        ADMIN_PASSWORD
                );

        assertThat(accessToken)
                .isNotBlank();

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        GATEWAY_BASE_URL
                                                + "/orders"
                                )
                        )
                        .timeout(
                                Duration.ofSeconds(10)
                        )
                        .header(
                                "Authorization",
                                "Bearer " + accessToken
                        )
                        .GET()
                        .build();

        HttpResponse<String> response =
                httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

        assertThat(response.statusCode())
                .withFailMessage(
                        """
                        Expected authenticated ADMIN request
                        through Gateway to succeed.

                        HTTP status: %s
                        Body: %s
                        """,
                        response.statusCode(),
                        response.body()
                )
                .isEqualTo(200);
    }
}