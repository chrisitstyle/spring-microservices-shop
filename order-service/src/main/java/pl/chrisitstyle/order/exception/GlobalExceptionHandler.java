package pl.chrisitstyle.order.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorDTO> handleValidation(
            MethodArgumentNotValidException exception
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();

        exception.getBindingResult()
                .getFieldErrors()
                .forEach(error ->
                        fieldErrors.putIfAbsent(
                                error.getField(),
                                error.getDefaultMessage()
                        )
                );

        return createErrorResponse(
                "Validation failed",
                HttpStatus.BAD_REQUEST,
                fieldErrors
        );
    }

    @ExceptionHandler(ExternalServiceException.class)
    public ResponseEntity<ErrorDTO> handleExternalService(
            ExternalServiceException exception
    ) {
        return createErrorResponse(
                exception.getMessage(),
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }

    @ExceptionHandler(OrderCreationException.class)
    public ResponseEntity<ErrorDTO> handleOrderCreation(
            OrderCreationException exception
    ) {
        return createErrorResponse(
                exception.getMessage(),
                HttpStatus.CONFLICT
        );
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorDTO> handleOrderNotFound(
            OrderNotFoundException exception
    ) {
        return createErrorResponse(
                exception.getMessage(),
                HttpStatus.NOT_FOUND
        );
    }

    @ExceptionHandler(InvalidOrderStatusTransitionException.class)
    public ResponseEntity<ErrorDTO> handleInvalidOrderStatusTransition(
            InvalidOrderStatusTransitionException exception
    ) {
        return createErrorResponse(
                exception.getMessage(),
                HttpStatus.CONFLICT
        );
    }

    @ExceptionHandler(OrderDeletionException.class)
    public ResponseEntity<ErrorDTO> handleOrderDeletion(
            OrderDeletionException exception
    ) {
        return createErrorResponse(
                exception.getMessage(),
                HttpStatus.CONFLICT
        );
    }

    private ResponseEntity<ErrorDTO> createErrorResponse(
            String message,
            HttpStatus status) {
        return createErrorResponse(
                message,
                status,
                Map.of());
    }

    private ResponseEntity<ErrorDTO> createErrorResponse(
            String message,
            HttpStatus status,
            Map<String, String> fieldErrors
    ) {
        ErrorDTO errorDTO = new ErrorDTO(
                status.value(),
                status.name(),
                message,
                Instant.now(),
                fieldErrors);

        return new ResponseEntity<>(errorDTO, status);
    }
}

