package com.stampedeio.gateway;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayIT {

    static MockWebServer backend;
    static KeyPair keyPair;
    static String kid = "test-key-1";

    @Autowired
    WebTestClient webClient;

    @BeforeAll
    static void startBackend() throws Exception {
        keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();

        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .keyID(kid)
                .build();
        String jwks = new JWKSet(rsaKey).toString();

        backend = new MockWebServer();
        backend.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().contains("/oauth2/jwks")) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody(jwks);
                }
                return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"status\":\"ok\"}");
            }
        });
        backend.start();
    }

    @AfterAll
    static void stopBackend() throws Exception {
        backend.shutdown();
    }

    @DynamicPropertySource
    static void backendProperties(DynamicPropertyRegistry registry) {
        registry.add("test.backend.url", () -> "http://localhost:" + backend.getPort());
        registry.add("test.jwk-set-uri", () -> "http://localhost:" + backend.getPort() + "/oauth2/jwks");
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
    void requestWithValidJwt_routesToBackend() throws Exception {
        String token = createValidJwt();

        webClient.get().uri("/api/v1/shows")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("ok");
    }

    @Test
    void correlationIdInjected_whenAbsent() throws Exception {
        String token = createValidJwt();

        webClient.get().uri("/api/v1/shows")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Correlation-ID");
    }

    @Test
    void correlationIdPreserved_whenPresent() throws Exception {
        String token = createValidJwt();
        String existingId = "my-trace-id-123";

        webClient.get().uri("/api/v1/shows")
                .header("Authorization", "Bearer " + token)
                .header("X-Correlation-ID", existingId)
                .exchange()
                .expectStatus().isOk()
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
        webClient.get().uri("/.well-known/openid-configuration")
                .exchange()
                .expectStatus().isOk();
    }

    private String createValidJwt() throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("test-user")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("scope", "openid profile email")
                .jwtID(UUID.randomUUID().toString())
                .build();

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(),
                claims);
        signedJWT.sign(new RSASSASigner(keyPair.getPrivate()));

        return signedJWT.serialize();
    }
}
