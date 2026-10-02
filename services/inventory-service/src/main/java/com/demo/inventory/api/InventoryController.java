package com.demo.inventory.api;

import com.demo.inventory.stock.StockStore;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private static final Logger log = LoggerFactory.getLogger(InventoryController.class);

    private final StockStore store;

    public InventoryController(StockStore store) {
        this.store = store;
    }

    @GetMapping
    public Collection<StockStore.Item> all() {
        return store.all();
    }

    @GetMapping("/{sku}")
    public StockStore.Item get(@PathVariable String sku) {
        return findOr404(sku);
    }

    @PostMapping("/{sku}/reserve")
    public StockStore.Reservation reserve(@PathVariable String sku, @RequestParam int quantity) {
        Span.current().setAttribute("inventory.sku", sku);
        Span.current().setAttribute("inventory.quantity", quantity);

        StockStore.Item item = findOr404(sku);
        return store.reserve(item, quantity)
                .map(r -> {
                    log.info("Reserved {} x {}, {} left", quantity, sku, r.remaining());
                    return r;
                })
                .orElseThrow(() -> {
                    log.warn("Insufficient stock for {}: requested {}, available {}", sku, quantity, item.getAvailable());
                    return new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient stock for " + sku);
                });
    }

    private StockStore.Item findOr404(String sku) {
        return store.find(sku).orElseThrow(() -> {
            log.warn("Unknown SKU requested: {}", sku);
            return new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown SKU " + sku);
        });
    }
}
