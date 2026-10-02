package com.demo.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findTop20ByOrderByIdDesc();
}
