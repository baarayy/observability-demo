package com.demo.order.domain;

import com.demo.order.inventory.InventoryClient;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final AttributeKey<String> SKU = AttributeKey.stringKey("order.sku");
    private static final AttributeKey<String> RESULT = AttributeKey.stringKey("order.result");

    private final OrderRepository repository;
    private final InventoryClient inventory;
    private final Tracer tracer;
    private final LongCounter ordersCounter;
    private final DoubleHistogram orderAmount;

    public OrderService(OrderRepository repository, InventoryClient inventory, OpenTelemetry otel) {
        this.repository = repository;
        this.inventory = inventory;
        this.tracer = otel.getTracer("com.demo.order");

        // Custom business metrics (manual instrumentation) -> Prometheus: orders_created_total, order_amount_*
        Meter meter = otel.getMeter("com.demo.order");
        this.ordersCounter = meter.counterBuilder("orders.created")
                .setDescription("Orders processed, by SKU and result")
                .setUnit("{order}")
                .build();
        this.orderAmount = meter.histogramBuilder("order.amount")
                .setDescription("Total value of confirmed orders in USD")
                // The default buckets are tuned for milliseconds (max 10000). Order values need their own,
                // otherwise every laptop order lands in +Inf and percentiles are capped at 10000.
                .setExplicitBucketBoundariesAdvice(List.of(25.0, 50.0, 100.0, 250.0, 500.0, 1_000.0, 2_500.0, 5_000.0, 10_000.0, 25_000.0))
                .build();
    }

    public Order placeOrder(String sku, int quantity) {
        // Enrich the auto-created SERVER span with business context (searchable in TraceQL)
        Span.current().setAttribute(SKU, sku);
        Span.current().setAttribute("order.quantity", quantity);
        log.info("Placing order sku={} quantity={}", sku, quantity);

        try {
            InventoryClient.Reservation reservation = inventory.reserve(sku, quantity);
            BigDecimal total = calculateTotal(reservation.unitPrice(), quantity);
            Order saved = repository.save(new Order(sku, quantity, reservation.unitPrice(), total));

            ordersCounter.add(1, Attributes.of(SKU, sku, RESULT, "confirmed"));
            orderAmount.record(total.doubleValue(), Attributes.of(SKU, sku));
            log.info("Order {} confirmed sku={} total={}", saved.getId(), sku, total);
            return saved;
        } catch (OrderException e) {
            ordersCounter.add(1, Attributes.of(SKU, sku, RESULT, e.reason()));
            throw e;
        }
    }

    /**
     * Example of a manual (custom) span: shows up as a child of the HTTP server span.
     */
    private BigDecimal calculateTotal(BigDecimal unitPrice, int quantity) {
        Span span = tracer.spanBuilder("calculate-total").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            // pretend to evaluate pricing rules
            Thread.sleep(ThreadLocalRandom.current().nextInt(5, 30));
            BigDecimal discount = quantity >= 10 ? new BigDecimal("0.10") : BigDecimal.ZERO;
            span.setAttribute("pricing.discount", discount.doubleValue());
            return unitPrice.multiply(BigDecimal.valueOf(quantity))
                    .multiply(BigDecimal.ONE.subtract(discount))
                    .setScale(2, RoundingMode.HALF_UP);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            span.end();
        }
    }
}
