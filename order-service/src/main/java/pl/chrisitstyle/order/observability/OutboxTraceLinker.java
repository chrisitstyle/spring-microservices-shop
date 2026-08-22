package pl.chrisitstyle.order.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pl.chrisitstyle.order.OutboxClaim;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class OutboxTraceLinker {

    private static final String TRACE_PARENT = "traceparent";
    private static final String TRACE_STATE = "tracestate";
    private static final String INSTRUMENTATION_SCOPE =
            "pl.chrisitstyle.order.outbox";

    private static final TextMapGetter<Map<String, String>> GETTER =
            new TextMapGetter<>() {

                @Override
                public Iterable<String> keys(Map<String, String> carrier) {
                    return carrier.keySet();
                }

                @Override
                public String get(Map<String, String> carrier, String key) {
                    if (carrier == null) {
                        return null;
                    }

                    return carrier.get(key);
                }
            };

    private final OpenTelemetry openTelemetry;

    public Span startLinkedSpan(OutboxClaim event) {
        SpanBuilder spanBuilder = openTelemetry
                .getTracer(INSTRUMENTATION_SCOPE)
                .spanBuilder("outbox publish")
                .setNoParent()
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("outbox.event.id", event.id().toString())
                .setAttribute("outbox.event.type", event.eventType())
                .setAttribute(
                        "outbox.aggregate.id",
                        event.aggregateId().toString()
                );

        SpanContext linkedSpanContext = extractSpanContext(event);

        if (linkedSpanContext.isValid()) {
            spanBuilder.addLink(linkedSpanContext);
        }

        return spanBuilder.startSpan();
    }

    private SpanContext extractSpanContext(OutboxClaim event) {
        if (event.traceParent() == null || event.traceParent().isBlank()) {
            return SpanContext.getInvalid();
        }

        Map<String, String> carrier = new HashMap<>();
        carrier.put(TRACE_PARENT, event.traceParent());

        if (event.traceState() != null && !event.traceState().isBlank()) {
            carrier.put(TRACE_STATE, event.traceState());
        }

        Context extractedContext = openTelemetry
                .getPropagators()
                .getTextMapPropagator()
                .extract(
                        Context.root(),
                        carrier,
                        GETTER
                );

        return Span.fromContext(extractedContext).getSpanContext();
    }
}