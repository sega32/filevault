package com.filevault.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Attaches a request id to every request, echoing an inbound {@code X-Request-Id} when the caller
 * supplies one, so a single upload can be followed across log lines and back to the client.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final int MAX_INBOUND_LENGTH = 64;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String requestId = sanitise(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** Caller-supplied ids are untrusted input: bound the length and strip anything exotic. */
    private static String sanitise(String inbound) {
        if (inbound == null || inbound.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String cleaned = inbound.trim().replaceAll("[^A-Za-z0-9._-]", "");
        if (cleaned.isEmpty()) {
            return UUID.randomUUID().toString();
        }
        return cleaned.length() > MAX_INBOUND_LENGTH
                ? cleaned.substring(0, MAX_INBOUND_LENGTH)
                : cleaned;
    }
}
