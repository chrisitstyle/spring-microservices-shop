package pl.chrisitstyle.user.exception;

import java.time.Instant;
import java.util.Map;

public record ErrorDTO(
        int status,
        String error,
        String message,
        Instant timestamp,
        Map<String, String> fieldErrors
) {
}
