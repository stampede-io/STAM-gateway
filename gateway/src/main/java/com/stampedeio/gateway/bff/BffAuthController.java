package com.stampedeio.gateway.bff;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Backend-for-Frontend token handler (ADR-0005 / STAM-440).
 *
 * <p>The SPA stays a public PKCE client and keeps its access token in memory.
 * This controller owns only the token-exchange leg: it proxies the code
 * exchange and the refresh to identity, strips the refresh token out of the
 * response, and keeps it in an httpOnly cookie scoped to {@code /api/v1/oauth2}.
 * The browser only ever sees {@code {access_token, expires_in, token_type}}.
 *
 * <p>The authorize step is not handled here — it is a top-level browser
 * navigation the gateway already routes straight to identity's
 * {@code /oauth2/authorize}.
 */
@RestController
@RequestMapping("/api/v1/oauth2")
public class BffAuthController {

    private static final Logger log = LoggerFactory.getLogger(BffAuthController.class);

    private static final String REFRESH_COOKIE = "stampede_rt";
    private static final String COOKIE_PATH = "/api/v1/oauth2";
    private static final String CORRELATION_ID = "X-Correlation-ID";
    private static final String SPA_CLIENT_ID = "stampede-spa";
    private static final Duration IDENTITY_TIMEOUT = Duration.ofSeconds(3);

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    private final WebClient identity;
    private final boolean cookieSecure;
    private final Duration refreshCookieTtl;

    public BffAuthController(
            @Value("${bff.identity-uri:${IDENTITY_URI:http://localhost:8084}}") String identityUri,
            @Value("${bff.cookie.secure:true}") boolean cookieSecure,
            // Same env var identity itself uses for REFRESH_TOKEN_TTL_DAYS — wire
            // both from one compose value so the cookie can't outlive (or expire
            // before) the token it's carrying.
            @Value("${bff.cookie.refresh-ttl-days:${REFRESH_TOKEN_TTL_DAYS:7}}") long refreshTtlDays) {
        this.identity = WebClient.create(identityUri);
        this.cookieSecure = cookieSecure;
        this.refreshCookieTtl = Duration.ofDays(refreshTtlDays);
    }

    /** Authorization-code exchange. Forwards the SPA's PKCE form to identity. */
    @PostMapping("/token")
    public Mono<ResponseEntity<Map<String, Object>>> token(
            ServerWebExchange exchange,
            @RequestHeader(value = CORRELATION_ID, required = false) String correlationId) {

        return exchange.getFormData()
                .flatMap(form -> exchangeWithIdentity(form, correlationId));
    }

    /** Refresh using the httpOnly cookie. Rotates the cookie to the new token. */
    @PostMapping("/refresh")
    public Mono<ResponseEntity<Map<String, Object>>> refresh(
            @CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken,
            @RequestHeader(value = CORRELATION_ID, required = false) String correlationId) {

        if (refreshToken == null || refreshToken.isBlank()) {
            return Mono.just(unauthorized(false));
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("client_id", SPA_CLIENT_ID);

        return exchangeWithIdentity(form, correlationId)
                .map(resp -> {
                    // Only a 4xx from identity means the token itself is bad
                    // (expired, revoked, reuse detected) — clear the cookie and
                    // send the SPA back through login. A 5xx is identity having
                    // a bad moment, not proof the session is invalid; pass it
                    // through as-is so the SPA can retry instead of being
                    // logged out over a transient outage.
                    if (resp.getStatusCode().is4xxClientError()) {
                        return unauthorized(true);
                    }
                    return resp;
                });
    }

    /** Drop the cookie. The refresh family self-destructs on next reuse. */
    @PostMapping("/logout")
    public Mono<ResponseEntity<Void>> logout() {
        return Mono.just(ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearedCookie().toString())
                .build());
    }

    private Mono<ResponseEntity<Map<String, Object>>> exchangeWithIdentity(
            MultiValueMap<String, String> form, String correlationId) {

        WebClient.RequestBodySpec request = identity.post()
                .uri("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (correlationId != null && !correlationId.isBlank()) {
            request = request.header(CORRELATION_ID, correlationId);
        }

        return request
                .body(BodyInserters.fromFormData(form))
                .exchangeToMono(upstream -> relayTokenResponse(upstream, correlationId))
                .timeout(IDENTITY_TIMEOUT)
                .onErrorResume(ex -> Mono.just(upstreamUnavailable(ex, correlationId)));
    }

    private Mono<ResponseEntity<Map<String, Object>>> relayTokenResponse(
            ClientResponse upstream, String correlationId) {
        return upstream.bodyToMono(JSON_MAP)
                .defaultIfEmpty(Map.of())
                .map(body -> {
                    if (!upstream.statusCode().is2xxSuccessful()) {
                        log.warn("identity token endpoint rejected request [correlationId={}]: {} {}",
                                correlationId, upstream.statusCode(), body.get("error"));
                        return ResponseEntity.status(upstream.statusCode())
                                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                                .body(problem(upstream.statusCode().value(), body));
                    }

                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("access_token", body.get("access_token"));
                    if (body.get("expires_in") != null) {
                        out.put("expires_in", body.get("expires_in"));
                    }
                    out.put("token_type", body.getOrDefault("token_type", "Bearer"));

                    ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                            .contentType(MediaType.APPLICATION_JSON);
                    Object refreshToken = body.get("refresh_token");
                    if (refreshToken != null) {
                        response.header(HttpHeaders.SET_COOKIE,
                                refreshCookie(refreshToken.toString()).toString());
                    }
                    return response.body(out);
                });
    }

    /** identity unreachable, timed out, or sent something that isn't JSON. */
    private ResponseEntity<Map<String, Object>> upstreamUnavailable(Throwable ex, String correlationId) {
        log.warn("identity token endpoint unreachable [correlationId={}]: {}", correlationId, ex.toString());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem(HttpStatus.BAD_GATEWAY.value(),
                        Map.of("error_description", "Identity is temporarily unavailable")));
    }

    private ResponseEntity<Map<String, Object>> unauthorized(boolean clearCookie) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(401)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (clearCookie) {
            response.header(HttpHeaders.SET_COOKIE, clearedCookie().toString());
        }
        return response.body(problem(401, Map.of("error_description", "No valid refresh session")));
    }

    private static Map<String, Object> problem(int status, Map<String, Object> upstreamBody) {
        Object detail = upstreamBody.get("error_description");
        if (detail == null) {
            detail = upstreamBody.getOrDefault("error", "token request failed");
        }
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", "Token request failed");
        problem.put("status", status);
        problem.put("detail", String.valueOf(detail));
        return problem;
    }

    private ResponseCookie refreshCookie(String value) {
        return baseCookie(value).maxAge(refreshCookieTtl).build();
    }

    private ResponseCookie clearedCookie() {
        return baseCookie("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path(COOKIE_PATH);
    }
}
