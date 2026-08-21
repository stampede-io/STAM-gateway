package com.stampedeio.gateway.filter;

import java.util.UUID;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

@Component
public class CorrelationIdFilter implements WebFilter, Ordered {

    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER);

        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        exchange.getResponse().getHeaders().set(CORRELATION_ID_HEADER, correlationId);

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
