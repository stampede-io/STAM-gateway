package com.stampedeio.gateway.filter;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.stampedeio.gateway.config.RateLimitProperties;
import com.stampedeio.gateway.config.RateLimitProperties.KeyType;

import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(prefix = "gateway.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
@SuppressWarnings("rawtypes")
public class RateLimitFilter implements GlobalFilter, Ordered {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> script;
    private final RateLimitProperties properties;

    public RateLimitFilter(ReactiveStringRedisTemplate redisTemplate,
                           RateLimitProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.script = new DefaultRedisScript<>();
        this.script.setLocation(new ClassPathResource("scripts/rate-limiter.lua"));
        this.script.setResultType(List.class);
    }

    @Override
    public int getOrder() {
        return 1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) {
            return chain.filter(exchange);
        }

        RateLimitProperties.RouteRateLimit routeLimit = properties.routes().get(route.getId());
        if (routeLimit == null) {
            return chain.filter(exchange);
        }

        return resolveKey(exchange, routeLimit.keyType())
                .flatMap(key -> checkRateLimit(route.getId(), key, routeLimit))
                .flatMap(result -> {
                    var headers = exchange.getResponse().getHeaders();
                    headers.set("X-RateLimit-Limit", String.valueOf(result.capacity()));
                    headers.set("X-RateLimit-Remaining",
                            String.valueOf(Math.max(0, result.remaining())));

                    if (!result.allowed()) {
                        headers.set("Retry-After", "1");
                        return writeTooManyRequests(exchange);
                    }
                    return chain.filter(exchange);
                });
    }

    private Mono<String> resolveKey(ServerWebExchange exchange, KeyType keyType) {
        return switch (keyType) {
            case USER -> exchange.getPrincipal()
                    .map(principal -> {
                        if (principal instanceof JwtAuthenticationToken jwt) {
                            return jwt.getToken().getSubject();
                        }
                        return principal.getName();
                    })
                    .defaultIfEmpty(resolveIp(exchange));
            case IP -> Mono.just(resolveIp(exchange));
        };
    }

    private String resolveIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote != null ? remote.getAddress().getHostAddress() : "unknown";
    }

    @SuppressWarnings("unchecked")
    private Mono<RateLimitResult> checkRateLimit(String routeId, String key,
                                                  RateLimitProperties.RouteRateLimit limit) {
        String prefix = "rate_limit:" + routeId + ":" + key;
        List<String> keys = List.of(prefix + ".tokens", prefix + ".ts");
        List<String> args = List.of(
                String.valueOf(limit.replenishRate()),
                String.valueOf(limit.burstCapacity()),
                String.valueOf(Instant.now().getEpochSecond()));

        return redisTemplate.execute(script, keys, args)
                .next()
                .map(result -> {
                    List<Long> values = (List<Long>) result;
                    boolean allowed = values.get(0) == 1L;
                    long remaining = values.get(1);
                    long capacity = values.get(2);
                    return new RateLimitResult(allowed, remaining, capacity);
                })
                .defaultIfEmpty(new RateLimitResult(true, 0, 0));
    }

    private Mono<Void> writeTooManyRequests(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        String body = """
                {"type":"about:blank","title":"Too Many Requests","status":429,\
                "detail":"Rate limit exceeded. Try again later."}""";

        var buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private record RateLimitResult(boolean allowed, long remaining, long capacity) {}
}
