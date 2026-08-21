package com.stampedeio.gateway.config;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        Map<String, RouteRateLimit> routes
) {

    public RateLimitProperties {
        if (routes == null) routes = Map.of();
    }

    public record RouteRateLimit(int replenishRate, int burstCapacity, KeyType keyType) {}

    public enum KeyType { USER, IP }
}
