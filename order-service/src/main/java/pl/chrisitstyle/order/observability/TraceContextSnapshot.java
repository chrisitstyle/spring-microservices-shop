package pl.chrisitstyle.order.observability;

public record TraceContextSnapshot(
        String traceParent,
        String traceState
) {
}
