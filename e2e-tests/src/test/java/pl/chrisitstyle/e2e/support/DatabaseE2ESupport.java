package pl.chrisitstyle.e2e.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class DatabaseE2ESupport {

    private final String userDbUrl =
            System.getProperty(
                    "e2e.user-db.url",
                    "jdbc:postgresql://localhost:5434/user_db"
            );

    private final String userDbUsername =
            System.getProperty(
                    "e2e.user-db.username",
                    "user_user"
            );

    private final String userDbPassword =
            System.getProperty(
                    "e2e.user-db.password",
                    "user_password"
            );

    private final String orderDbUrl =
            System.getProperty(
                    "e2e.order-db.url",
                    "jdbc:postgresql://localhost:5435/order_db"
            );

    private final String orderDbUsername =
            System.getProperty(
                    "e2e.order-db.username",
                    "order_user"
            );

    private final String orderDbPassword =
            System.getProperty(
                    "e2e.order-db.password",
                    "order_password"
            );

    private final String notificationDbUrl =
            System.getProperty(
                    "e2e.notification-db.url",
                    "jdbc:postgresql://localhost:5436/notification_db"
            );

    private final String notificationDbUsername =
            System.getProperty(
                    "e2e.notification-db.username",
                    "notification_user"
            );

    private final String notificationDbPassword =
            System.getProperty(
                    "e2e.notification-db.password",
                    "notification_password"
            );

    public Long ensureShopUser(
            String nickname,
            String email,
            String keycloakSubject
    ) throws Exception {

        try (
                Connection connection =
                        DriverManager.getConnection(
                                userDbUrl,
                                userDbUsername,
                                userDbPassword
                        )
        ) {

            connection.setAutoCommit(false);

            try {

                Long existingBySubject =
                        findUserIdBySubject(
                                connection,
                                keycloakSubject
                        );

                if (existingBySubject != null) {

                    updateUser(
                            connection,
                            existingBySubject,
                            nickname,
                            email,
                            keycloakSubject
                    );

                    connection.commit();

                    return existingBySubject;
                }

                Long existingByEmail =
                        findUserIdByEmail(
                                connection,
                                email
                        );

                if (existingByEmail != null) {

                    updateUser(
                            connection,
                            existingByEmail,
                            nickname,
                            email,
                            keycloakSubject
                    );

                    connection.commit();

                    return existingByEmail;
                }

                Long userId =
                        insertUser(
                                connection,
                                nickname,
                                email,
                                keycloakSubject
                        );

                connection.commit();

                return userId;

            } catch (Exception exception) {

                connection.rollback();

                throw exception;
            }
        }
    }

    public String findSagaStatusByOrderId(
            Long orderId
    ) throws Exception {

        try (
                Connection connection =
                        DriverManager.getConnection(
                                orderDbUrl,
                                orderDbUsername,
                                orderDbPassword
                        );

                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                SELECT status
                                FROM order_creation_sagas
                                WHERE order_id = ?
                                ORDER BY created_at DESC
                                LIMIT 1
                                """
                        )
        ) {

            statement.setLong(
                    1,
                    orderId
            );

            try (
                    ResultSet resultSet =
                            statement.executeQuery()
            ) {

                if (!resultSet.next()) {
                    return null;
                }

                return resultSet.getString(
                        "status"
                );
            }
        }
    }

    public boolean isOrderCreatedEventPublished(
            Long orderId
    ) throws Exception {

        try (
                Connection connection =
                        DriverManager.getConnection(
                                orderDbUrl,
                                orderDbUsername,
                                orderDbPassword
                        );

                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                SELECT published_at
                                FROM outbox_events
                                WHERE aggregate_id = ?
                                  AND event_type = 'OrderCreated'
                                ORDER BY created_at DESC
                                LIMIT 1
                                """
                        )
        ) {

            statement.setLong(
                    1,
                    orderId
            );

            try (
                    ResultSet resultSet =
                            statement.executeQuery()
            ) {

                if (!resultSet.next()) {
                    return false;
                }

                return resultSet.getObject(
                        "published_at"
                ) != null;
            }
        }
    }

    public boolean isNotificationProcessed(
            Long orderId
    ) throws Exception {

        try (
                Connection connection =
                        DriverManager.getConnection(
                                notificationDbUrl,
                                notificationDbUsername,
                                notificationDbPassword
                        );

                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                SELECT 1
                                FROM processed_order_events
                                WHERE order_id = ?
                                """
                        )
        ) {

            statement.setLong(
                    1,
                    orderId
            );

            try (
                    ResultSet resultSet =
                            statement.executeQuery()
            ) {

                return resultSet.next();
            }
        }
    }

    private Long findUserIdBySubject(
            Connection connection,
            String keycloakSubject
    ) throws Exception {

        try (
                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                SELECT id
                                FROM users
                                WHERE keycloak_subject = ?
                                """
                        )
        ) {

            statement.setString(
                    1,
                    keycloakSubject
            );

            try (
                    ResultSet resultSet =
                            statement.executeQuery()
            ) {

                if (!resultSet.next()) {
                    return null;
                }

                return resultSet.getLong(
                        "id"
                );
            }
        }
    }

    private Long findUserIdByEmail(
            Connection connection,
            String email
    ) throws Exception {

        try (
                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                SELECT id
                                FROM users
                                WHERE LOWER(email) = LOWER(?)
                                """
                        )
        ) {

            statement.setString(
                    1,
                    email
            );

            try (
                    ResultSet resultSet =
                            statement.executeQuery()
            ) {

                if (!resultSet.next()) {
                    return null;
                }

                return resultSet.getLong(
                        "id"
                );
            }
        }
    }

    private void updateUser(
            Connection connection,
            Long userId,
            String nickname,
            String email,
            String keycloakSubject
    ) throws Exception {

        try (
                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                UPDATE users
                                SET nickname = ?,
                                    email = ?,
                                    active = TRUE,
                                    keycloak_subject = ?
                                WHERE id = ?
                                """
                        )
        ) {

            statement.setString(
                    1,
                    nickname
            );

            statement.setString(
                    2,
                    email
            );

            statement.setString(
                    3,
                    keycloakSubject
            );

            statement.setLong(
                    4,
                    userId
            );

            int updatedRows =
                    statement.executeUpdate();

            if (updatedRows != 1) {

                throw new IllegalStateException(
                        "Expected one shop user to be updated"
                );
            }
        }
    }

    private Long insertUser(
            Connection connection,
            String nickname,
            String email,
            String keycloakSubject
    ) throws Exception {

        try (
                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                INSERT INTO users (
                                    nickname,
                                    email,
                                    active,
                                    keycloak_subject,
                                    created_at
                                )
                                VALUES (?, ?, TRUE, ?, CURRENT_TIMESTAMP)
                                RETURNING id
                                """
                        )
        ) {

            statement.setString(
                    1,
                    nickname
            );

            statement.setString(
                    2,
                    email
            );

            statement.setString(
                    3,
                    keycloakSubject
            );

            try (
                    ResultSet resultSet =
                            statement.executeQuery()
            ) {

                if (!resultSet.next()) {

                    throw new IllegalStateException(
                            "Shop user insert returned no ID"
                    );
                }

                return resultSet.getLong(
                        "id"
                );
            }
        }
    }

    public long countOrdersByUserId(
            Long userId
    ) throws Exception {

        try (
                Connection connection =
                        DriverManager.getConnection(
                                orderDbUrl,
                                orderDbUsername,
                                orderDbPassword
                        );

                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                SELECT COUNT(*)
                                FROM orders
                                WHERE user_id = ?
                                """
                        )
        ) {

            statement.setLong(
                    1,
                    userId
            );

            try (
                    ResultSet resultSet =
                            statement.executeQuery()
            ) {

                resultSet.next();

                return resultSet.getLong(1);
            }
        }
    }

    public long countOrderCreatedEvents()
            throws Exception {

        try (
                Connection connection =
                        DriverManager.getConnection(
                                orderDbUrl,
                                orderDbUsername,
                                orderDbPassword
                        );

                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                SELECT COUNT(*)
                                FROM outbox_events
                                WHERE event_type = 'OrderCreated'
                                """
                        );

                ResultSet resultSet =
                        statement.executeQuery()
        ) {

            resultSet.next();

            return resultSet.getLong(1);
        }
    }

    public String findLatestSagaStatusByUserId(
            Long userId
    ) throws Exception {

        try (
                Connection connection =
                        DriverManager.getConnection(
                                orderDbUrl,
                                orderDbUsername,
                                orderDbPassword
                        );

                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                SELECT status
                                FROM order_creation_sagas
                                WHERE user_id = ?
                                ORDER BY created_at DESC
                                LIMIT 1
                                """
                        )
        ) {

            statement.setLong(
                    1,
                    userId
            );

            try (
                    ResultSet resultSet =
                            statement.executeQuery()
            ) {

                if (!resultSet.next()) {
                    return null;
                }

                return resultSet.getString(
                        "status"
                );
            }
        }
    }
}