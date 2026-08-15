package com.stampedeio.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.Jwt;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureWebTestClient
class GatewayIT {

    @Autowired
    WebTestClient webClient;

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    @Test
    void requestWithoutJwt_returns401() {
        webClient.get().uri("/api/v1/shows")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.title").isEqualTo("Unauthorized");
    }

    @Test
    void requestWithInvalidJwt_returns401() {
        when(jwtDecoder.decode(anyString()))
                .thenReturn(Mono.error(new org.springframework.security.oauth2.jwt.BadJwtException("Invalid token")));

        webClient.get().uri("/api/v1/shows")
                .header("Authorization", "Bearer invalid.token.here")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType("application/problem+json");
    }

    @Test
    void requestWithValidJwt_isAuthenticated() {
        webClient.mutateWith(mockJwt().jwt(jwt -> jwt
                        .subject("test-user")
                        .claim("scope", "openid profile email")))
                .get().uri("/api/v1/shows")
                .exchange()
                .expectStatus().is5xx();
        // 5xx expected: JWT is valid and passes security, but no real backend
        // is running — the gateway attempts to route and gets connection refused.
        // This proves the request passed authentication (not 401).
    }

    @Test
    void correlationIdInjected_whenAbsent() {
        webClient.mutateWith(mockJwt())
                .get().uri("/api/v1/shows")
                .exchange()
                .expectHeader().exists("X-Correlation-ID");
    }

    @Test
    void correlationIdPreserved_whenPresent() {
        String existingId = "my-trace-id-123";

        webClient.mutateWith(mockJwt())
                .get().uri("/api/v1/shows")
                .header("X-Correlation-ID", existingId)
                .exchange()
                .expectHeader().valueEquals("X-Correlation-ID", existingId);
    }

    @Test
    void healthEndpoint_isPermittedWithoutJwt() {
        webClient.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void publicOAuthEndpoints_arePermittedWithoutJwt() {
        webClient.get().uri("/api/v1/users/register")
                .exchange()
                .expectStatus().is5xx();
        // 5xx expected: no backend — proves the request passed security (not 401).
    }
}
