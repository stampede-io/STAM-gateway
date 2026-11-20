package com.stampedeio.gateway;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STAM-449: Stripe authenticates its webhook with a Stripe-Signature HMAC over
 * the raw body, never a bearer token. The gateway must let that one POST
 * through to payment untouched (payment's Webhook.constructEvent is the gate)
 * while every other /api/v1/payments/** path still needs a JWT.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class StripeWebhookRoutingIT {

    // Spacing and key order matter: the HMAC is over these exact bytes.
    private static final String STRIPE_BODY =
            "{\n  \"id\": \"evt_1\",  \"type\":\"payment_intent.succeeded\",\"amount\":1999}";
    private static final String SIGNATURE = "t=1700000000,v1=deadbeef";

    private static final AtomicReference<String> receivedPath = new AtomicReference<>();
    private static final AtomicReference<String> receivedBody = new AtomicReference<>();
    private static final AtomicReference<String> receivedSignature = new AtomicReference<>();

    static DisposableServer payment;

    @Autowired
    WebTestClient webClient;

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    // Own port, not the shared 19999 stub: a cached context's pooled keep-alive
    // connection to a previous class's stub would answer instead of this one.
    @DynamicPropertySource
    static void paymentRoute(DynamicPropertyRegistry registry) {
        startPayment();
        registry.add("test.payment-uri",
                () -> "http://localhost:" + payment.port());
    }

    static void startPayment() {
        if (payment != null) {
            return;
        }
        payment = HttpServer.create()
                .port(0)
                .handle((req, res) -> req.receive().aggregate().asString(StandardCharsets.UTF_8)
                        .defaultIfEmpty("")
                        .flatMap(body -> {
                            receivedPath.set(req.uri());
                            receivedBody.set(body);
                            receivedSignature.set(req.requestHeaders().get("Stripe-Signature"));
                            return res.status(200).send().then();
                        }))
                .bindNow();
    }

    @AfterAll
    static void stopPayment() {
        if (payment != null) {
            payment.disposeNow();
        }
    }

    @BeforeEach
    void reset() {
        receivedPath.set(null);
        receivedBody.set(null);
        receivedSignature.set(null);
    }

    @Test
    void webhookWithoutJwt_isRoutedToPayment_withBodyAndSignatureUnchanged() {
        webClient.post().uri("/api/v1/payments/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", SIGNATURE)
                .bodyValue(STRIPE_BODY)
                .exchange()
                .expectStatus().isOk();

        assertThat(receivedPath.get()).isEqualTo("/api/v1/payments/webhook");
        assertThat(receivedBody.get()).isEqualTo(STRIPE_BODY);
        assertThat(receivedSignature.get()).isEqualTo(SIGNATURE);
    }

    @Test
    void otherPaymentPaths_stillNeedJwt() {
        webClient.post().uri("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isUnauthorized();

        webClient.post().uri("/api/v1/payments/refunds")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isUnauthorized();

        // only POST is opened; a GET on the same path is not
        webClient.get().uri("/api/v1/payments/webhook")
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(receivedPath.get()).isNull();
    }
}
