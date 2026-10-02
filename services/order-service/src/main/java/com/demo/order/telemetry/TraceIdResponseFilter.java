package com.demo.order.telemetry;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Returns the trace id to the caller as an X-Trace-Id header, so you can copy it from
 * curl / k6 output and paste it straight into Grafana -> Explore -> Tempo.
 */
@Component
public class TraceIdResponseFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SpanContext ctx = Span.current().getSpanContext();
        if (ctx.isValid()) {
            response.setHeader("X-Trace-Id", ctx.getTraceId());
        }
        chain.doFilter(request, response);
    }
}
