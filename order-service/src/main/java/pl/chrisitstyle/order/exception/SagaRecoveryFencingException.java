package pl.chrisitstyle.order.exception;

public class SagaRecoveryFencingException
        extends IllegalStateException {

    public SagaRecoveryFencingException(
            String message
    ) {
        super(message);
    }
}
