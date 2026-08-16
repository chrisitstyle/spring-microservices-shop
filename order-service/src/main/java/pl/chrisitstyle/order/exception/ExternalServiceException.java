package pl.chrisitstyle.order.exception;

public class ExternalServiceException extends RuntimeException {

    public ExternalServiceException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
