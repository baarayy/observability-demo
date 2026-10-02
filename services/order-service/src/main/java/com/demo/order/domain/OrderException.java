package com.demo.order.domain;

import org.springframework.http.HttpStatus;

/**
 * Business failures of the order flow. Each one carries a short machine-friendly reason
 * that is used as a metric attribute (low cardinality!) and an HTTP status.
 */
public abstract class OrderException extends RuntimeException {

    private final HttpStatus status;
    private final String reason;

    protected OrderException(HttpStatus status, String reason, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.reason = reason;
    }

    public HttpStatus status() {
        return status;
    }

    public String reason() {
        return reason;
    }

    public static class UnknownProduct extends OrderException {
        public UnknownProduct(String sku) {
            super(HttpStatus.NOT_FOUND, "unknown_product", "Unknown product: " + sku, null);
        }
    }

    public static class OutOfStock extends OrderException {
        public OutOfStock(String sku, int quantity) {
            super(HttpStatus.CONFLICT, "out_of_stock", "Not enough stock for " + sku + " (requested " + quantity + ")", null);
        }
    }

    public static class InventoryUnavailable extends OrderException {
        public InventoryUnavailable(Throwable cause) {
            super(HttpStatus.SERVICE_UNAVAILABLE, "inventory_unavailable", "Inventory service call failed: " + cause.getMessage(), cause);
        }
    }
}
