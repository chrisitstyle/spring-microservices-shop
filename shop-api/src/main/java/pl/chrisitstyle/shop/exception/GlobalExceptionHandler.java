package pl.chrisitstyle.shop.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {


    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ErrorDTO> handleProductNotFound(
            ProductNotFoundException exception
    ) {
        return createErrorResponse(exception.getMessage(), HttpStatus.NOT_FOUND);
    }


    private ResponseEntity<ErrorDTO> createErrorResponse(String message, HttpStatus status) {
        ErrorDTO errorDTO = new ErrorDTO(
                status.value(),
                message,
                status.name(),
                Instant.now());
        return new ResponseEntity<>(errorDTO, status);
    }
}



