package com.demo.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sku;

    private int quantity;

    private BigDecimal unitPrice;

    private BigDecimal total;

    private Instant createdAt;

    protected Order() {
    }

    public Order(String sku, int quantity, BigDecimal unitPrice, BigDecimal total) {
        this.sku = sku;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.total = total;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
