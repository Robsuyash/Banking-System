package com.banking.apigateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ServerWebExchange;

import java.util.function.Function;

@Configuration
public class RateLimiterConfig {

    @Bean
    public Function<ServerWebExchange, String> keyResolver() {
        return exchange -> exchange.getRequest()
                .getRemoteAddress()
                .getAddress()
                .getHostAddress();
    }
}

