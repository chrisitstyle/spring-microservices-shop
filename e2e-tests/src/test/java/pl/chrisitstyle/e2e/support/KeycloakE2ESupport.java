package pl.chrisitstyle.e2e.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public class KeycloakE2ESupport {

    private static final String REALM =
            "spring-shop";

    private static final String TEST_CLIENT_ID =
            "e2e-test-client";

    private final String keycloakBaseUrl =
            System.getProperty(
                    "e2e.keycloak.base-url",
                    "http://localhost:8080"
            );

    private final String adminUsername =
            System.getProperty(
                    "e2e.keycloak.admin.username",
                    "admin"
            );

    private final String adminPassword =
            System.getProperty(
                    "e2e.keycloak.admin.password",
                    "admin"
            );

    private final HttpClient httpClient =
            HttpClient.newBuilder()
                    .connectTimeout(
                            Duration.ofSeconds(5)
                    )
                    .build();

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    public void ensureTestClient()
            throws Exception {

        String adminToken =
                getAdminAccessToken();

        JsonNode existingClients =
                sendJson(
                        HttpRequest.newBuilder()
                                .uri(
                                        URI.create(
                                                keycloakBaseUrl
                                                        + "/admin/realms/"
                                                        + REALM
                                                        + "/clients?clientId="
                                                        + encode(TEST_CLIENT_ID)
                                        )
                                )
                                .header(
                                        "Authorization",
                                        "Bearer " + adminToken
                                )
                                .GET()
                                .build(),
                        200
                );

        if (!existingClients.isEmpty()) {
            return;
        }

        Map<String, Object> client =
                new LinkedHashMap<>();

        client.put(
                "clientId",
                TEST_CLIENT_ID
        );

        client.put(
                "enabled",
                true
        );

        client.put(
                "publicClient",
                true
        );

        client.put(
                "bearerOnly",
                false
        );

        client.put(
                "standardFlowEnabled",
                false
        );

        client.put(
                "implicitFlowEnabled",
                false
        );

        client.put(
                "directAccessGrantsEnabled",
                true
        );

        client.put(
                "serviceAccountsEnabled",
                false
        );

        client.put(
                "protocol",
                "openid-connect"
        );

        client.put(
                "fullScopeAllowed",
                true
        );

        sendWithoutBody(
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        keycloakBaseUrl
                                                + "/admin/realms/"
                                                + REALM
                                                + "/clients"
                                )
                        )
                        .header(
                                "Authorization",
                                "Bearer " + adminToken
                        )
                        .header(
                                "Content-Type",
                                "application/json"
                        )
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        objectMapper.writeValueAsString(
                                                client
                                        )
                                )
                        )
                        .build(),
                201
        );
    }

    public void ensureRealmUser(
            String username,
            String password,
            String realmRole
    ) throws Exception {

        String adminToken =
                getAdminAccessToken();

        String userId =
                findUserId(
                        adminToken,
                        username
                );

        if (userId == null) {

            Map<String, Object> user =
                    new LinkedHashMap<>();

            user.put(
                    "username",
                    username
            );

            user.put(
                    "enabled",
                    true
            );

            user.put(
                    "emailVerified",
                    true
            );

            sendWithoutBody(
                    HttpRequest.newBuilder()
                            .uri(
                                    URI.create(
                                            keycloakBaseUrl
                                                    + "/admin/realms/"
                                                    + REALM
                                                    + "/users"
                                    )
                            )
                            .header(
                                    "Authorization",
                                    "Bearer " + adminToken
                            )
                            .header(
                                    "Content-Type",
                                    "application/json"
                            )
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            objectMapper.writeValueAsString(
                                                    user
                                            )
                                    )
                            )
                            .build(),
                    201
            );

            userId =
                    findUserId(
                            adminToken,
                            username
                    );

            if (userId == null) {
                throw new IllegalStateException(
                        "Keycloak user was created but cannot be found: "
                                + username
                );
            }
        }

        completeUserProfile(
                adminToken,
                userId,
                username
        );


        resetPassword(
                adminToken,
                userId,
                password
        );

        assignRealmRole(
                adminToken,
                userId,
                realmRole
        );
    }

    public String getUserAccessToken(
            String username,
            String password
    ) throws Exception {

        Map<String, String> form =
                new LinkedHashMap<>();

        form.put(
                "grant_type",
                "password"
        );

        form.put(
                "client_id",
                TEST_CLIENT_ID
        );

        form.put(
                "username",
                username
        );

        form.put(
                "password",
                password
        );

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        keycloakBaseUrl
                                                + "/realms/"
                                                + REALM
                                                + "/protocol/openid-connect/token"
                                )
                        )
                        .header(
                                "Content-Type",
                                "application/x-www-form-urlencoded"
                        )
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        formBody(form)
                                )
                        )
                        .build();

        JsonNode response =
                sendJson(
                        request,
                        200
                );

        return response
                .path("access_token")
                .asText();
    }

    private String getAdminAccessToken()
            throws Exception {

        Map<String, String> form =
                new LinkedHashMap<>();

        form.put(
                "grant_type",
                "password"
        );

        form.put(
                "client_id",
                "admin-cli"
        );

        form.put(
                "username",
                adminUsername
        );

        form.put(
                "password",
                adminPassword
        );

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        keycloakBaseUrl
                                                + "/realms/master/protocol/openid-connect/token"
                                )
                        )
                        .header(
                                "Content-Type",
                                "application/x-www-form-urlencoded"
                        )
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        formBody(form)
                                )
                        )
                        .build();

        JsonNode response =
                sendJson(
                        request,
                        200
                );

        return response
                .path("access_token")
                .asText();
    }

    private void completeUserProfile(
            String adminToken,
            String userId,
            String username
    ) throws Exception {

        Map<String, Object> user =
                new LinkedHashMap<>();

        user.put(
                "username",
                username
        );

        user.put(
                "enabled",
                true
        );

        user.put(
                "firstName",
                "E2E"
        );

        user.put(
                "lastName",
                "Test User"
        );

        user.put(
                "email",
                username + "@example.test"
        );

        user.put(
                "emailVerified",
                true
        );

        user.put(
                "requiredActions",
                new String[0]
        );

        sendWithoutBody(
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        keycloakBaseUrl
                                                + "/admin/realms/"
                                                + REALM
                                                + "/users/"
                                                + userId
                                )
                        )
                        .header(
                                "Authorization",
                                "Bearer " + adminToken
                        )
                        .header(
                                "Content-Type",
                                "application/json"
                        )
                        .PUT(
                                HttpRequest.BodyPublishers.ofString(
                                        objectMapper.writeValueAsString(
                                                user
                                        )
                                )
                        )
                        .build(),
                204
        );
    }

    private String findUserId(
            String adminToken,
            String username
    ) throws Exception {

        JsonNode users =
                sendJson(
                        HttpRequest.newBuilder()
                                .uri(
                                        URI.create(
                                                keycloakBaseUrl
                                                        + "/admin/realms/"
                                                        + REALM
                                                        + "/users?username="
                                                        + encode(username)
                                                        + "&exact=true"
                                        )
                                )
                                .header(
                                        "Authorization",
                                        "Bearer " + adminToken
                                )
                                .GET()
                                .build(),
                        200
                );

        if (users.isEmpty()) {
            return null;
        }

        return users
                .get(0)
                .path("id")
                .asText();
    }

    private void resetPassword(
            String adminToken,
            String userId,
            String password
    ) throws Exception {

        Map<String, Object> credential =
                new LinkedHashMap<>();

        credential.put(
                "type",
                "password"
        );

        credential.put(
                "value",
                password
        );

        credential.put(
                "temporary",
                false
        );

        sendWithoutBody(
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        keycloakBaseUrl
                                                + "/admin/realms/"
                                                + REALM
                                                + "/users/"
                                                + userId
                                                + "/reset-password"
                                )
                        )
                        .header(
                                "Authorization",
                                "Bearer " + adminToken
                        )
                        .header(
                                "Content-Type",
                                "application/json"
                        )
                        .PUT(
                                HttpRequest.BodyPublishers.ofString(
                                        objectMapper.writeValueAsString(
                                                credential
                                        )
                                )
                        )
                        .build(),
                204
        );
    }

    private void assignRealmRole(
            String adminToken,
            String userId,
            String roleName
    ) throws Exception {

        JsonNode role =
                sendJson(
                        HttpRequest.newBuilder()
                                .uri(
                                        URI.create(
                                                keycloakBaseUrl
                                                        + "/admin/realms/"
                                                        + REALM
                                                        + "/roles/"
                                                        + encode(roleName)
                                        )
                                )
                                .header(
                                        "Authorization",
                                        "Bearer " + adminToken
                                )
                                .GET()
                                .build(),
                        200
                );

        JsonNode currentRoles =
                sendJson(
                        HttpRequest.newBuilder()
                                .uri(
                                        URI.create(
                                                keycloakBaseUrl
                                                        + "/admin/realms/"
                                                        + REALM
                                                        + "/users/"
                                                        + userId
                                                        + "/role-mappings/realm"
                                        )
                                )
                                .header(
                                        "Authorization",
                                        "Bearer " + adminToken
                                )
                                .GET()
                                .build(),
                        200
                );

        boolean alreadyAssigned =
                false;

        for (JsonNode currentRole : currentRoles) {

            if (
                    roleName.equals(
                            currentRole
                                    .path("name")
                                    .asText()
                    )
            ) {

                alreadyAssigned =
                        true;

                break;
            }
        }

        if (alreadyAssigned) {
            return;
        }

        String body =
                objectMapper.writeValueAsString(
                        new Object[]{
                                role
                        }
                );

        sendWithoutBody(
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        keycloakBaseUrl
                                                + "/admin/realms/"
                                                + REALM
                                                + "/users/"
                                                + userId
                                                + "/role-mappings/realm"
                                )
                        )
                        .header(
                                "Authorization",
                                "Bearer " + adminToken
                        )
                        .header(
                                "Content-Type",
                                "application/json"
                        )
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        body
                                )
                        )
                        .build(),
                204
        );
    }

    private JsonNode sendJson(
            HttpRequest request,
            int expectedStatus
    ) throws Exception {

        HttpResponse<String> response =
                httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

        requireStatus(
                response,
                expectedStatus
        );

        return objectMapper.readTree(
                response.body()
        );
    }

    private void sendWithoutBody(
            HttpRequest request,
            int expectedStatus
    ) throws Exception {

        HttpResponse<String> response =
                httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

        requireStatus(
                response,
                expectedStatus
        );
    }

    private void requireStatus(
            HttpResponse<String> response,
            int expectedStatus
    ) {

        if (
                response.statusCode()
                        != expectedStatus
        ) {

            throw new IllegalStateException(
                    "Expected HTTP "
                            + expectedStatus
                            + " but received "
                            + response.statusCode()
                            + ". Body: "
                            + response.body()
            );
        }
    }

    private String formBody(
            Map<String, String> values
    ) {

        return values
                .entrySet()
                .stream()
                .map(
                        entry ->
                                encode(entry.getKey())
                                        + "="
                                        + encode(
                                        entry.getValue()
                                )
                )
                .reduce(
                        (left, right) ->
                                left + "&" + right
                )
                .orElse("");
    }

    public String getRealmUserId(
            String username
    ) throws Exception {

        String adminToken =
                getAdminAccessToken();

        String userId =
                findUserId(
                        adminToken,
                        username
                );

        if (userId == null) {
            throw new IllegalStateException(
                    "Keycloak user not found: " + username
            );
        }

        return userId;
    }

    private String encode(
            String value
    ) {

        return URLEncoder.encode(
                value,
                StandardCharsets.UTF_8
        );
    }
}