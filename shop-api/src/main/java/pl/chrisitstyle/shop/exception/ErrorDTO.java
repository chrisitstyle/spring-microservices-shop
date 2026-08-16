package pl.chrisitstyle.shop.exception;

import java.time.Instant;

public record ErrorDTO(
        int status,
        String error,
        String message,
        Instant timestamp
) {
}
