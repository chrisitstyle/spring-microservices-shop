ALTER TABLE users
    ADD COLUMN keycloak_subject VARCHAR(255);

CREATE UNIQUE INDEX ux_users_keycloak_subject
    ON users (keycloak_subject)
    WHERE keycloak_subject IS NOT NULL;