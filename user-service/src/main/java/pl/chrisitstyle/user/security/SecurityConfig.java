package pl.chrisitstyle.user.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import pl.chrisitstyle.user.KeycloakRealmRolesConverter;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http
    ) throws Exception {

        JwtAuthenticationConverter jwtAuthenticationConverter =
                new JwtAuthenticationConverter();

        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(
                new KeycloakRealmRolesConverter()
        );

        jwtAuthenticationConverter.setPrincipalClaimName(
                "preferred_username"
        );

        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )
                .authorizeHttpRequests(authorize ->
                        authorize
                                .requestMatchers("/actuator/**")
                                .permitAll()

                                .requestMatchers(
                                        HttpMethod.GET,
                                        "/users/by-keycloak-subject"
                                )
                                .hasRole("INTERNAL_SERVICE")

                                .requestMatchers(
                                        HttpMethod.GET,
                                        "/users"
                                )
                                .hasRole("ADMIN")

                                .requestMatchers(
                                        HttpMethod.GET,
                                        "/users/*"
                                )
                                .hasAnyRole(
                                        "ADMIN",
                                        "INTERNAL_SERVICE"
                                )

                                .requestMatchers(
                                        HttpMethod.POST,
                                        "/users"
                                )
                                .hasRole("ADMIN")

                                .requestMatchers(
                                        HttpMethod.PUT,
                                        "/users/*"
                                )
                                .hasRole("ADMIN")

                                .requestMatchers(
                                        HttpMethod.DELETE,
                                        "/users/*"
                                )
                                .hasRole("ADMIN")

                                .anyRequest()
                                .denyAll()
                )
                .oauth2ResourceServer(oauth2 ->
                        oauth2.jwt(jwt ->
                                jwt.jwtAuthenticationConverter(
                                        jwtAuthenticationConverter
                                )
                        )
                )
                .build();
    }
}