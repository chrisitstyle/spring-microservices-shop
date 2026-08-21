package pl.chrisitstyle.user.exception;

public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(Long id) {
        super("User not found: " + id);
    }

    public UserNotFoundException(String keycloakSubject) {
        super(
                "User not found for Keycloak subject: "
                        + keycloakSubject
        );
    }
}
