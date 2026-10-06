package com.echcherqaoui.orderflow.payment.repository;

import com.echcherqaoui.orderflow.payment.model.Payment;
import com.echcherqaoui.orderflow.payment.model.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    boolean existsByOrderId(UUID orderId);

    <T> Optional<T> findByOrderId(UUID orderId, Class<T> type);

    Optional<Payment> findByPaymentIntentId(String paymentIntentId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE Payment p
        SET p.status = 'REFUND_PENDING',
            p.version = p.version + 1
        WHERE p.orderId = :orderId
          AND p.status = 'SUCCESS'
    """)
    int markRefundPending(@Param("orderId") UUID orderId);

    @Query("SELECT p.status FROM Payment p WHERE p.orderId = :orderId")
    Optional<PaymentStatus> findStatusByOrderId(@Param("orderId") UUID orderId);
}
