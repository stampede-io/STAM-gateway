package com.stampedeio.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;

import com.github.tomakehurst.wiremock.WireMockServer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the BFF token handler (ADR-0005): the refresh token from identity is
 * moved into an httpOnly cookie and never reaches the client body, the cookie
 * is rotated on refresh, and dropped on logout.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class BffAuthIT {

    static WireMockServer identity = new WireMockServer(wireMockConfig().dynamicPort());

    @Autowired
    WebTestClient web;

    @BeforeAll
    static void startIdentity() {
        identity.start();
    }

    @AfterAll
    static void stopIdentity() {
        identity.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("bff.identity-uri", identity::baseUrl);
        registry.add("bff.cookie.secure", () -> false);
    }

    @BeforeEach
    void resetStubs() {
        identity.resetAll();
    }

    private void stubIdentityToken(String responseBody, int status) {
        identity.stubFor(post(urlEqualTo("/oauth2/token")).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(responseBody)));
    }

    private static MultiValueMap<String, String> codeExchangeForm() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", "auth-code-1");
        form.add("redirect_uri", "http://localhost:5173/auth/callback");
        form.add("client_id", "stampede-spa");
        form.add("code_verifier", "verifier-1");
        return form;
    }

    @Test
    void tokenExchange_movesRefreshTokenIntoHttpOnlyCookie() {
        stubIdentityToken("""
                {"access_token":"at-1","refresh_token":"rt-1","expires_in":600,"token_type":"Bearer"}
                """, 200);

        web.post().uri("/api/v1/oauth2/token")
                .body(BodyInserters.fromFormData(codeExchangeForm()))
                .exchange()
                .expectStatus().isOk()
                .expectCookie().httpOnly("stampede_rt", true)
                .expectCookie().path("stampede_rt", "/api/v1/oauth2")
                .expectCookie().value("stampede_rt", v -> assertThat(v).isEqualTo("rt-1"))
                .expectBody()
                .jsonPath("$.access_token").isEqualTo("at-1")
                .jsonPath("$.refresh_token").doesNotExist();
    }

    @Test
    void refresh_withCookie_rotatesTheCookie() {
        stubIdentityToken("""
                {"access_token":"at-2","refresh_token":"rt-2","expires_in":600,"token_type":"Bearer"}
                """, 200);

        web.post().uri("/api/v1/oauth2/refresh")
                .cookie("stampede_rt", "rt-1")
                .exchange()
                .expectStatus().isOk()
                .expectCookie().value("stampede_rt", v -> assertThat(v).isEqualTo("rt-2"))
                .expectBody()
                .jsonPath("$.access_token").isEqualTo("at-2")
                .jsonPath("$.refresh_token").doesNotExist();
    }

    @Test
    void refresh_withoutCookie_returns401() {
        web.post().uri("/api/v1/oauth2/refresh")
                .exchange()
                .expectStatus().isUnauthorized();
        assertThat(identity.getAllServeEvents()).isEmpty();
    }

    @Test
    void refresh_whenIdentityRejects_clearsCookieAnd401s() {
        stubIdentityToken("""
                {"error":"invalid_grant","error_description":"refresh token reuse detected"}
                """, 400);

        web.post().uri("/api/v1/oauth2/refresh")
                .cookie("stampede_rt", "stale-rt")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectCookie().maxAge("stampede_rt", java.time.Duration.ZERO);
    }

    @Test
    void logout_dropsTheCookie() {
        web.post().uri("/api/v1/oauth2/logout")
                .cookie("stampede_rt", "rt-1")
                .exchange()
                .expectStatus().isNoContent()
                .expectCookie().maxAge("stampede_rt", java.time.Duration.ZERO);
    }

    @Test
    void tokenExchange_propagatesIdentityErrorAsProblemJson() {
        stubIdentityToken("""
                {"error":"invalid_grant","error_description":"bad code_verifier"}
                """, 400);

        web.post().uri("/api/v1/oauth2/token")
                .body(BodyInserters.fromFormData(codeExchangeForm()))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.detail").isEqualTo("bad code_verifier");
    }
}
