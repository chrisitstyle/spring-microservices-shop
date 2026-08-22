package pl.chrisitstyle.order.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class TraceContextCapture {

    private static final String TRACE_PARENT = "traceparent";
    private static final String TRACE_STATE = "tracestate";

    private static final TextMapSetter<Map<String, String>> SETTER =
            (carrier, key, value) -> carrier.put(key, value);

    private final OpenTelemetry openTelemetry;

    public TraceContextSnapshot capture() {
        Map<String, String> carrier = new HashMap<>();

        openTelemetry.getPropagators()
                .getTextMapPropagator()
                .inject(
                        Context.current(),
                        carrier,
                        SETTER
                );

        return new TraceContextSnapshot(
                carrier.get(TRACE_PARENT),
                carrier.get(TRACE_STATE)
        );
    }
}