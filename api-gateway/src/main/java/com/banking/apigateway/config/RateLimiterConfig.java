package com.banking.apigateway.config;

import org.springframework.context.annotation.Configuration;

/**
 * RateLimiterConfig - Disabled for Gateway MVC
 *
 * Note: The reactive rate limiter (KeyResolver with ServerWebExchange)
 * is not compatible with Spring Cloud Gateway MVC (servlet-based).
 *
 * Gateway MVC uses servlet stack and does not support reactive filters
 * like RequestRateLimiter that were designed for reactive Gateway.
 *
 * For rate limiting in Gateway MVC, consider:
 * - Servlet filters with libraries like Bucket4j
 * - Redis-based rate limiting with RestTemplate
 * - Third-party API gateway solutions
 */
@Configuration
public class RateLimiterConfig {

    // Reactive KeyResolver removed - incompatible with Gateway MVC

    // TODO: Implement servlet-based rate limiting if needed
}

