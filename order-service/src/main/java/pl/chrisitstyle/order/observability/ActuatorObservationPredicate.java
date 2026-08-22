package pl.chrisitstyle.order.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

@Component
public class ActuatorObservationPredicate implements ObservationPredicate {

    private static final String ACTUATOR_PREFIX = "/actuator";

    @Override
    public boolean test(String name, Observation.Context context) {
        if (context instanceof ServerRequestObservationContext serverContext) {
            String requestUri = serverContext.getCarrier().getRequestURI();
            return !requestUri.startsWith(ACTUATOR_PREFIX);
        }

        return true;
    }
}
