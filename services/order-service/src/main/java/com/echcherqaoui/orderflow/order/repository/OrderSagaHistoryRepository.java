package com.echcherqaoui.orderflow.order.repository;

import com.echcherqaoui.orderflow.order.model.OrderSagaHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderSagaHistoryRepository extends JpaRepository<OrderSagaHistory, Long> {
}