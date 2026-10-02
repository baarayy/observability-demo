package com.demo.inventory.stock;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory stock. Kept deliberately simple: the interesting part of this service is
 * how it behaves (and fails) as a downstream dependency of order-service.
 */
@Component
public class StockStore {

    private static final Logger log = LoggerFactory.getLogger(StockStore.class);
    private static final AttributeKey<String> SKU = AttributeKey.stringKey("inventory.sku");
    private static final int RESTOCK_THRESHOLD = 100;
    private static final int RESTOCK_AMOUNT = 400;

    public static final class Item {
        private final String sku;
        private final String name;
        private final BigDecimal unitPrice;
        private int available;

        Item(String sku, String name, String unitPrice, int available) {
            this.sku = sku;
            this.name = name;
            this.unitPrice = new BigDecimal(unitPrice);
            this.available = available;
        }

        public String getSku() {
            return sku;
        }

        public String getName() {
            return name;
        }

        public BigDecimal getUnitPrice() {
            return unitPrice;
        }

        public synchronized int getAvailable() {
            return available;
        }
    }

    public record Reservation(String sku, int reserved, int remaining, BigDecimal unitPrice) {
    }

    private final Map<String, Item> items = new LinkedHashMap<>();

    public StockStore(OpenTelemetry otel) {
        add(new Item("KEYBOARD", "Mechanical keyboard", "89.90", 500));
        add(new Item("MOUSE", "Wireless mouse", "29.50", 500));
        add(new Item("MONITOR", "27\" 4K monitor", "349.00", 200));
        add(new Item("LAPTOP", "14\" laptop", "1299.00", 150));
        add(new Item("HEADSET", "Noise-cancelling headset", "199.99", 300));

        // Observable gauge, one time series per SKU -> inventory_stock_level{inventory_sku="..."}
        otel.getMeter("com.demo.inventory")
                .gaugeBuilder("inventory.stock.level")
                .setDescription("Units currently available per SKU")
                .setUnit("{item}")
                .ofLongs()
                .buildWithCallback(m -> items.values()
                        .forEach(i -> m.record(i.getAvailable(), Attributes.of(SKU, i.getSku()))));
    }

    private void add(Item item) {
        items.put(item.getSku(), item);
    }

    public Collection<Item> all() {
        return items.values();
    }

    public Optional<Item> find(String sku) {
        return Optional.ofNullable(items.get(sku));
    }

    /** @return the reservation, or empty if there is not enough stock */
    public Optional<Reservation> reserve(Item item, int quantity) {
        synchronized (item) {
            if (item.available < quantity) {
                return Optional.empty();
            }
            item.available -= quantity;
            return Optional.of(new Reservation(item.sku, quantity, item.available, item.unitPrice));
        }
    }

    /** Background job (also traced by the agent) that keeps the demo from running dry. */
    @Scheduled(fixedRate = 30_000)
    public void restock() {
        for (Item item : items.values()) {
            synchronized (item) {
                if (item.available < RESTOCK_THRESHOLD) {
                    item.available += RESTOCK_AMOUNT;
                    log.info("Restocked {} by {} units, now {}", item.sku, RESTOCK_AMOUNT, item.available);
                }
            }
        }
    }
}
