package com.stampedeio.gateway;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class GatewayIT {

    @Autowired
    WebTestClient webClient;

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        when(jwtDecoder.decode(eq("valid-token"))).thenReturn(Mono.just(
                new Jwt("valid-token",
                        Instant.now(), Instant.now().plusSeconds(300),
                        Map.of("alg", "RS256"),
                        Map.of("sub", "test-user", "scope", "openid profile email"))));

        when(jwtDecoder.decode(eq("invalid.token.here"))).thenReturn(
                Mono.error(new org.springframework.security.oauth2.jwt.BadJwtException("Invalid token")));
    }

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
        webClient.get().uri("/api/v1/shows")
                .header("Authorization", "Bearer invalid.token.here")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType("application/problem+json");
    }

    @Test
    void requestWithValidJwt_passesAuthentication() {
        webClient.get().uri("/api/v1/shows")
                .header("Authorization", "Bearer valid-token")
                .exchange()
                .expectStatus().value(status -> {
                    assert status != 401 && status != 403 :
                            "Expected request to pass authentication but got " + status;
                });
    }

    @Test
    void correlationIdInjected_whenAbsent() {
        webClient.get().uri("/api/v1/shows")
                .header("Authorization", "Bearer valid-token")
                .exchange()
                .expectHeader().exists("X-Correlation-ID");
    }

    @Test
    void correlationIdPreserved_whenPresent() {
        String existingId = "my-trace-id-123";

        webClient.get().uri("/api/v1/shows")
                .header("Authorization", "Bearer valid-token")
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
    void publicRegistrationEndpoint_isPermittedWithoutJwt() {
        webClient.post().uri("/api/v1/users/register")
                .exchange()
                .expectStatus().value(status -> {
                    assert status != 401 && status != 403 :
                            "Expected public endpoint to not require auth but got " + status;
                });
    }
}
