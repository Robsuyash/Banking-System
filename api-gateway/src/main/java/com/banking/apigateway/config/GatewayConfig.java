package com.banking.apigateway.config;

import org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

@Configuration
public class GatewayConfig {

    @Bean
    public RouterFunction<ServerResponse> accountServiceRoute() {
        return route("account-service")
                .route(RequestPredicates.path("/api/v1/accounts/**"), http())
                .before(uri("http://localhost:8095"))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> transactionServiceRoute() {
        return route("transaction-service")
                .route(RequestPredicates.path("/api/v1/transactions/**"), http())
                .before(uri("http://localhost:8094"))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> paymentServiceRoute() {
        return route("payment-service")
                .route(RequestPredicates.path("/api/v1/payments/**"), http())
                .before(uri("http://localhost:8093"))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> fraudDetectionServiceRoute() {
        return route("fraud-detection-service")
                .route(RequestPredicates.path("/api/fraud/**"), http())
                .before(uri("http://localhost:8091"))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> notificationServiceRoute() {
        return route("notification-service")
                .route(RequestPredicates.path("/api/notifications/**"), http())
                .before(uri("http://localhost:8092"))
                .build();
    }
}
