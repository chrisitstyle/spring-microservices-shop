package pl.chrisitstyle.order.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter
    ) throws Exception {

        http
                .csrf(csrf -> csrf.disable())

                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                .authorizeHttpRequests(authorize ->
                        authorize

                                // Monitoring endpoints are public for now.
                                .requestMatchers("/actuator/**")
                                .permitAll()

                                // Only administrators can list all orders.
                                .requestMatchers(HttpMethod.GET, "/orders")
                                .hasRole("ADMIN")

                                .requestMatchers(HttpMethod.POST, "/orders")
                                .hasRole("CUSTOMER")

                                .requestMatchers(HttpMethod.GET, "/orders/me")
                                .hasRole("CUSTOMER")

                                // Customers and administrators can read a single order.
                                .requestMatchers(HttpMethod.GET, "/orders/*")
                                .hasAnyRole("CUSTOMER", "ADMIN")

                                // Only administrators can change order status.
                                .requestMatchers(
                                        HttpMethod.PATCH,
                                        "/orders/*/status"
                                )
                                .hasRole("ADMIN")

                                // Only administrators can delete orders.
                                .requestMatchers(HttpMethod.DELETE, "/orders/*")
                                .hasRole("ADMIN")

                                .anyRequest()
                                .authenticated()
                )

                .oauth2ResourceServer(oauth2 ->
                        oauth2.jwt(jwt ->
                                jwt.jwtAuthenticationConverter(
                                        jwtAuthenticationConverter
                                )
                        )
                );

        return http.build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter =
                new JwtAuthenticationConverter();

        converter.setJwtGrantedAuthoritiesConverter(
                new KeycloakRealmRolesConverter()
        );

        converter.setPrincipalClaimName(
                "preferred_username"
        );

        return converter;
    }
}