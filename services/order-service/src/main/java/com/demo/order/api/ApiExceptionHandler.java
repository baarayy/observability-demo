package com.demo.order.api;

import com.demo.order.domain.OrderException;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(OrderException.class)
    public ResponseEntity<ProblemDetail> handleOrderException(OrderException e) {
        Span span = Span.current();
        span.setAttribute("error.reason", e.reason());

        if (e.status().is5xxServerError()) {
            // Server-side fault: log at ERROR with the stack trace and attach the exception to the span
            log.error("Order failed: {}", e.getMessage(), e);
            span.recordException(e);
        } else {
            // Client-side problem (bad SKU, no stock): not our fault, WARN is enough
            log.warn("Order rejected: {}", e.getMessage());
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        problem.setProperty("reason", e.reason());
        problem.setProperty("traceId", span.getSpanContext().getTraceId());
        return ResponseEntity.status(e.status()).body(problem);
    }
}
