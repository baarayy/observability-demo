package com.demo.order.api;

import com.demo.order.domain.Order;
import com.demo.order.domain.OrderRepository;
import com.demo.order.domain.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    public record CreateOrderRequest(@NotBlank String sku, @Min(1) @Max(10_000) int quantity) {
    }

    private final OrderService orderService;
    private final OrderRepository repository;

    public OrderController(OrderService orderService, OrderRepository repository) {
        this.orderService = orderService;
        this.repository = repository;
    }

    @PostMapping
    public ResponseEntity<Order> create(@Valid @RequestBody CreateOrderRequest request) {
        Order order = orderService.placeOrder(request.sku(), request.quantity());
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @GetMapping("/{id}")
    public Order get(@PathVariable long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order " + id + " not found"));
    }

    @GetMapping
    public List<Order> latest() {
        return repository.findTop20ByOrderByIdDesc();
    }
}
