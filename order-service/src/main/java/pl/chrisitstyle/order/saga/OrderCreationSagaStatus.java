package pl.chrisitstyle.order.saga;

public enum OrderCreationSagaStatus {

    STARTED,

    RESERVING_STOCK,

    STOCK_RESERVED,

    COMPLETING_ORDER,

    COMPLETED,

    COMPENSATING,

    COMPENSATED,

    COMPENSATION_FAILED,

    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED
                || this == COMPENSATED
                || this == FAILED;
    }
}