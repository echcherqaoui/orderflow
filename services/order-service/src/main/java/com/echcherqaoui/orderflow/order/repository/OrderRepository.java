package com.echcherqaoui.orderflow.order.repository;

import com.echcherqaoui.orderflow.order.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {
    boolean existsByCartId(String cartId);
}