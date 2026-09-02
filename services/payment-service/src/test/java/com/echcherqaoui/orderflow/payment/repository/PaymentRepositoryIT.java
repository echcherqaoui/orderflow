package com.echcherqaoui.orderflow.payment.repository;


import com.echcherqaoui.orderflow.payment.AbstractIntegrationTest;
import com.echcherqaoui.orderflow.payment.model.Payment;
import com.echcherqaoui.orderflow.payment.model.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PaymentRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    void existsByOrderId_returnsTrue_whenPaymentExists() {
        UUID orderId = UUID.randomUUID();

        Payment payment = new Payment()
              .setOrderId(orderId)
              .setPaymentIntentId("pi_" + UUID.randomUUID())
              .setUserId("user-" + UUID.randomUUID())
              .setTotalAmountCents(5000L)
              .setStatus(PaymentStatus.PENDING);

        paymentRepository.save(payment);

        boolean exists = paymentRepository.existsByOrderId(orderId);

        assertThat(exists).isTrue();
    }

    @Test
    void existsByOrderId_returnsFalse_whenPaymentDoesNotExist() {
        UUID nonExistentOrderId = UUID.randomUUID();

        boolean exists = paymentRepository.existsByOrderId(nonExistentOrderId);

        assertThat(exists).isFalse();
    }
}