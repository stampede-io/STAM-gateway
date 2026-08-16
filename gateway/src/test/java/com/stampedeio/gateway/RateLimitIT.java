package com.stampedeio.gateway;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "gateway.rate-limit.enabled=true",
        "gateway.rate-limit.routes.booking.replenish-rate=5",
        "gateway.rate-limit.routes.booking.burst-capacity=5",
        "gateway.rate-limit.routes.booking.key-type=USER",
        "gateway.rate-limit.routes.identity.replenish-rate=3",
        "gateway.rate-limit.routes.identity.burst-capacity=3",
        "gateway.rate-limit.routes.identity.key-type=IP",
        "management.health.redis.enabled=true"
})
@Import(RateLimitIT.TestRoutes.class)
@Testcontainers
class RateLimitIT {

    static DisposableServer mockDownstream;

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    WebTestClient webClient;

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    @TestConfiguration
    static class TestRoutes {
        @Bean
        RouteLocator testRouteLocator(RouteLocatorBuilder builder) {
            return builder.routes()
                    .route("booking", r -> r
                            .path("/api/v1/reservations/**", "/api/v1/bookings/**")
                            .uri("http://localhost:19999"))
                    .route("identity", r -> r
                            .path("/oauth2/**", "/.well-known/**", "/login", "/api/v1/users/**")
                            .uri("http://localhost:19999"))
                    .build();
        }
    }

    @BeforeAll
    static void startMockDownstream() {
        mockDownstream = HttpServer.create()
                .port(19999)
                .handle((req, res) -> res.status(200)
                        .header("Content-Type", "application/json")
                        .sendString(Mono.just("{}")))
                .bindNow();
    }

    @AfterAll
    static void stopMockDownstream() {
        if (mockDownstream != null) {
            mockDownstream.disposeNow();
        }
    }

    private Jwt buildJwt(String token, String subject) {
        return new Jwt(token,
                Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"),
                Map.of("sub", subject, "scope", "openid"));
    }

    @BeforeEach
    void setUp() {
        when(jwtDecoder.decode(eq("burst-user-1-token")))
                .thenReturn(Mono.just(buildJwt("burst-user-1-token", "burst-user-1")));
        when(jwtDecoder.decode(eq("burst-user-2-token")))
                .thenReturn(Mono.just(buildJwt("burst-user-2-token", "burst-user-2")));
        when(jwtDecoder.decode(eq("header-check-token")))
                .thenReturn(Mono.just(buildJwt("header-check-token", "header-check-user")));
        when(jwtDecoder.decode(eq("user-a-token")))
                .thenReturn(Mono.just(buildJwt("user-a-token", "user-A")));
        when(jwtDecoder.decode(eq("user-b-token")))
                .thenReturn(Mono.just(buildJwt("user-b-token", "user-B")));
    }

    @Test
    void authenticatedBurst_returns429AfterLimit() {
        int allowed = 0;
        int rejected = 0;

        for (int i = 0; i < 10; i++) {
            var result = webClient.post().uri("/api/v1/reservations")
                    .header("Authorization", "Bearer burst-user-1-token")
                    .exchange()
                    .returnResult(String.class);

            if (result.getStatus().value() == 429) {
                rejected++;
            } else {
                allowed++;
            }
        }

        assert allowed == 5 : "Expected 5 allowed but got " + allowed;
        assert rejected == 5 : "Expected 5 rejected but got " + rejected;
    }

    @Test
    void rateLimitedResponse_has429WithRetryAfterAndProblemJson() {
        for (int i = 0; i < 5; i++) {
            webClient.post().uri("/api/v1/reservations")
                    .header("Authorization", "Bearer burst-user-2-token")
                    .exchange();
        }

        webClient.post().uri("/api/v1/reservations")
                .header("Authorization", "Bearer burst-user-2-token")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Retry-After", "1")
                .expectHeader().exists("X-RateLimit-Limit")
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0")
                .expectHeader().contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.status").isEqualTo(429)
                .jsonPath("$.title").isEqualTo("Too Many Requests");
    }

    @Test
    void rateLimitHeaders_presentOnAllowedRequests() {
        webClient.post().uri("/api/v1/reservations")
                .header("Authorization", "Bearer header-check-token")
                .exchange()
                .expectHeader().exists("X-RateLimit-Limit")
                .expectHeader().exists("X-RateLimit-Remaining");
    }

    @Test
    void ipBasedRateLimit_appliesOnAuthEndpoint() {
        int rejected = 0;

        for (int i = 0; i < 6; i++) {
            var result = webClient.post().uri("/oauth2/token")
                    .exchange()
                    .returnResult(String.class);

            if (result.getStatus().value() == 429) {
                rejected++;
            }
        }

        assert rejected >= 3 : "Expected at least 3 rejections on IP rate limit but got " + rejected;
    }

    @Test
    void differentUsers_haveIndependentLimits() {
        for (int i = 0; i < 5; i++) {
            webClient.post().uri("/api/v1/reservations")
                    .header("Authorization", "Bearer user-a-token")
                    .exchange();
        }

        webClient.post().uri("/api/v1/reservations")
                .header("Authorization", "Bearer user-b-token")
                .exchange()
                .expectStatus().value(status -> {
                    assert status != 429 :
                            "User B should not be rate-limited by User A's requests";
                });
    }
}
