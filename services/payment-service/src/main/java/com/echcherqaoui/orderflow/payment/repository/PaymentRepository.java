package com.echcherqaoui.orderflow.payment.repository;

import com.echcherqaoui.orderflow.payment.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    boolean existsByOrderId(UUID orderId);

    <T> Optional<T> findByOrderId(UUID orderId, Class<T> type);

    Optional<Payment> findByPaymentIntentId(String paymentIntentId);
}
