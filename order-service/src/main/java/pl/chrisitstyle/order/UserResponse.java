package pl.chrisitstyle.order;

import java.time.OffsetDateTime;

public record UserResponse(
        Long id,
        String nickname,
        String email,
        Boolean active,
        OffsetDateTime createdAt
) {
}
