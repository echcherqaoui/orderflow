package com.echcherqaoui.orderflow.order.repository;

import com.echcherqaoui.orderflow.order.model.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {
}