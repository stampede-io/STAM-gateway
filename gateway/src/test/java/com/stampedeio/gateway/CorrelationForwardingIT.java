package com.stampedeio.gateway;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * GatewayIT only asserts on the response header, which is why the gateway
 * minting an ID it never forwarded went unnoticed. This asserts on what the
 * downstream service actually receives.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class CorrelationForwardingIT {

    private static final String HEADER = "X-Correlation-ID";
    private static final AtomicReference<String> receivedByDownstream = new AtomicReference<>();

    static DisposableServer downstream;

    @Autowired
    WebTestClient webClient;

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    @BeforeAll
    static void startDownstream() {
        downstream = HttpServer.create()
                .port(19999)
                .handle((req, res) -> {
                    receivedByDownstream.set(req.requestHeaders().get(HEADER));
                    return res.status(200)
                            .header("Content-Type", "application/json")
                            .sendString(Mono.just("{}"));
                })
                .bindNow();
    }

    @AfterAll
    static void stopDownstream() {
        if (downstream != null) {
            downstream.disposeNow();
        }
    }

    @BeforeEach
    void setUp() {
        receivedByDownstream.set(null);
        when(jwtDecoder.decode(eq("valid-token"))).thenReturn(Mono.just(
                new Jwt("valid-token",
                        Instant.now(), Instant.now().plusSeconds(300),
                        Map.of("alg", "RS256"),
                        Map.of("sub", "test-user", "scope", "openid"))));
    }

    @Test
    void mintedId_isForwardedDownstream_andMatchesResponse() {
        String onResponse = webClient.get().uri("/api/v1/reservations/00000000-0000-0000-0000-000000000000")
                .header("Authorization", "Bearer valid-token")
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseHeaders().getFirst(HEADER);

        assertThat(onResponse).isNotBlank();
        assertThat(receivedByDownstream.get()).isEqualTo(onResponse);
    }

    @Test
    void clientSuppliedId_isForwardedUnchanged() {
        webClient.get().uri("/api/v1/reservations/00000000-0000-0000-0000-000000000000")
                .header("Authorization", "Bearer valid-token")
                .header(HEADER, "my-trace-id-123")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HEADER, "my-trace-id-123");

        assertThat(receivedByDownstream.get()).isEqualTo("my-trace-id-123");
    }

    @Test
    void blankId_isTreatedAsAbsent() {
        String onResponse = webClient.get().uri("/api/v1/reservations/00000000-0000-0000-0000-000000000000")
                .header("Authorization", "Bearer valid-token")
                .header(HEADER, "")
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseHeaders().getFirst(HEADER);

        assertThat(onResponse).isNotBlank();
        assertThat(receivedByDownstream.get()).isEqualTo(onResponse);
    }
}
