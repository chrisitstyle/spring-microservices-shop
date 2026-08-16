package pl.chrisitstyle.shop.exception;

public class OrderDeletionException extends RuntimeException {
    public OrderDeletionException(String message) {
        super(message);
    }
}
