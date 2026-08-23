package pl.chrisitstyle.order.saga;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SagaRecoveryWorkerIdentity {

    private final String value;

    public SagaRecoveryWorkerIdentity(
            Environment environment
    ) {
        String configuredName =
                environment.getProperty(
                        "app.saga.recovery.worker-name"
                );

        String baseName;

        if (configuredName == null
                || configuredName.isBlank()) {

            baseName = environment.getProperty(
                    "HOSTNAME",
                    "local"
            );

        } else {
            baseName = configuredName;
        }

        this.value =
                baseName
                        + "-"
                        + UUID.randomUUID()
                        .toString()
                        .substring(0, 8);
    }

    public String value() {
        return value;
    }
}