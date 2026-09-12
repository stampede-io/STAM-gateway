package com.stampedeio.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;

import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        .pathMatchers(
                                "/oauth2/**",
                                "/.well-known/**",
                                "/login",
                                "/api/v1/oauth2/**",
                                "/api/v1/users/register"
                        ).permitAll()
                        // Catalog reads are public (CLAUDE.md §16); writes still need a JWT.
                        .pathMatchers(HttpMethod.GET,
                                "/api/v1/venues/**",
                                "/api/v1/events/**",
                                "/api/v1/shows/**",
                                "/api/v1/seats/**"
                        ).permitAll()
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> {})
                        .authenticationEntryPoint(problemJsonEntryPoint())
                )
                .build();
    }

    @Bean
    ServerAuthenticationEntryPoint problemJsonEntryPoint() {
        return (exchange, ex) -> {
            var response = exchange.getResponse();
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

            String body = """
                    {"type":"about:blank","title":"Unauthorized","status":401,"detail":"%s"}"""
                    .formatted(ex.getMessage().replace("\"", "'"));

            var buffer = response.bufferFactory().wrap(body.getBytes());
            return response.writeWith(Mono.just(buffer));
        };
    }
}
