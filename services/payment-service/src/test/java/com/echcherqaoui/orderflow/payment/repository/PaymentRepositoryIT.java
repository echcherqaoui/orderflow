package com.echcherqaoui.orderflow.payment.repository;

import com.echcherqaoui.orderflow.payment.dto.PaymentCancelProjection;
import com.echcherqaoui.orderflow.payment.model.Payment;
import com.echcherqaoui.orderflow.payment.model.PaymentStatus;
import com.echcherqaoui.orderflow.payment.support.WithPostgres;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PaymentRepositoryIT implements WithPostgres {

    @Autowired
    private PaymentRepository paymentRepository;

    @Nested
    @DisplayName("existsByOrderId()")
    class ExistsByOrderId {

        @Test
        @DisplayName("returns true when payment entity exists for given order ID")
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
        @DisplayName("returns false when payment entity does not exist for given order ID")
        void existsByOrderId_returnsFalse_whenPaymentDoesNotExist() {
            UUID nonExistentOrderId = UUID.randomUUID();

            boolean exists = paymentRepository.existsByOrderId(nonExistentOrderId);

            assertThat(exists).isFalse();
        }
    }

    @Nested
    @DisplayName("findByOrderId()")
    class FindByOrderId {

        @Test
        @DisplayName("returns projection when payment exists for given order ID")
        void findCancelProjectionByOrderId_returnsProjection_whenPaymentExists() {
            UUID orderId = UUID.randomUUID();
            String intentId = "pi_" + UUID.randomUUID();

            Payment payment = new Payment()
                  .setOrderId(orderId)
                  .setPaymentIntentId(intentId)
                  .setUserId("user-" + UUID.randomUUID())
                  .setTotalAmountCents(5000L)
                  .setStatus(PaymentStatus.PENDING);

            paymentRepository.save(payment);

            Optional<PaymentCancelProjection> projectionOpt = paymentRepository.findByOrderId(orderId, PaymentCancelProjection.class);

            assertThat(projectionOpt).isPresent();
            assertThat(projectionOpt.get().status()).isEqualTo(PaymentStatus.PENDING);
            assertThat(projectionOpt.get().paymentIntentId()).isEqualTo(intentId);
        }

        @Test
        @DisplayName("returns projection with null paymentIntentId when payment exists without intent")
        void findCancelProjectionByOrderId_returnsProjectionWithNullIntent_whenPaymentIntentIdIsNull() {
            UUID orderId = UUID.randomUUID();

            Payment payment = new Payment()
                  .setOrderId(orderId)
                  .setPaymentIntentId(null)
                  .setUserId("user-" + UUID.randomUUID())
                  .setTotalAmountCents(5000L)
                  .setStatus(PaymentStatus.FAILED);

            paymentRepository.save(payment);

            Optional<PaymentCancelProjection> projectionOpt = paymentRepository.findByOrderId(orderId, PaymentCancelProjection.class);

            assertThat(projectionOpt).isPresent();
            assertThat(projectionOpt.get().status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(projectionOpt.get().paymentIntentId()).isNull();
        }

        @Test
        @DisplayName("returns empty optional when projection is requested for non-existent order ID")
        void findCancelProjectionByOrderId_returnsEmpty_whenPaymentDoesNotExist() {
            UUID nonExistentOrderId = UUID.randomUUID();

            Optional<PaymentCancelProjection> projectionOpt = paymentRepository.findByOrderId(nonExistentOrderId, PaymentCancelProjection.class);

            assertThat(projectionOpt).isEmpty();
        }

        @Test
        @DisplayName("returns entity when payment exists for given order ID")
        void findByOrderId_returnsPayment_whenPaymentExists() {
            UUID orderId = UUID.randomUUID();

            Payment payment = new Payment()
                  .setOrderId(orderId)
                  .setPaymentIntentId("pi_" + UUID.randomUUID())
                  .setUserId("user-" + UUID.randomUUID())
                  .setTotalAmountCents(5000L)
                  .setStatus(PaymentStatus.PENDING);

            paymentRepository.save(payment);

            Optional<Payment> paymentOpt = paymentRepository.findByOrderId(orderId, Payment.class);

            assertThat(paymentOpt).isPresent();
            assertThat(paymentOpt.get().getOrderId()).isEqualTo(orderId);
        }

        @Test
        @DisplayName("returns empty optional when entity is requested for non-existent order ID")
        void findByOrderId_returnsEmpty_whenPaymentDoesNotExist() {
            UUID nonExistentOrderId = UUID.randomUUID();

            Optional<Payment> paymentOpt = paymentRepository.findByOrderId(nonExistentOrderId, Payment.class);

            assertThat(paymentOpt).isEmpty();
        }
    }

    @Nested
    @DisplayName("findByPaymentIntentId()")
    class FindByPaymentIntentId {

        @Test
        @DisplayName("returns entity when payment exists for given payment intent ID")
        void findByPaymentIntentId_returnsPayment_whenPaymentExists() {
            String paymentIntentId = "pi_" + UUID.randomUUID();

            Payment payment = new Payment()
                  .setOrderId(UUID.randomUUID())
                  .setPaymentIntentId(paymentIntentId)
                  .setUserId("user-" + UUID.randomUUID())
                  .setTotalAmountCents(5000L)
                  .setStatus(PaymentStatus.PENDING);

            paymentRepository.save(payment);

            Optional<Payment> paymentOpt = paymentRepository.findByPaymentIntentId(paymentIntentId);

            assertThat(paymentOpt).isPresent();
            assertThat(paymentOpt.get().getPaymentIntentId()).isEqualTo(paymentIntentId);
        }

        @Test
        @DisplayName("returns empty optional when payment does not exist for given payment intent ID")
        void findByPaymentIntentId_returnsEmpty_whenPaymentDoesNotExist() {
            String nonExistentPaymentIntentId = "pi_" + UUID.randomUUID();

            Optional<Payment> paymentOpt = paymentRepository.findByPaymentIntentId(nonExistentPaymentIntentId);

            assertThat(paymentOpt).isEmpty();
        }
    }
}