package com.echcherqaoui.orderflow.payment.model;

import com.echcherqaoui.orderflow.payment.repository.PaymentRepository;
import com.echcherqaoui.orderflow.payment.support.WithPostgres;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class PaymentEntityConstraintIT  implements WithPostgres {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PaymentRepository paymentRepository;

    private Payment newPayment() {
        return new Payment()
              .setOrderId(UUID.randomUUID())
              .setPaymentIntentId("pi_" + UUID.randomUUID())
              .setUserId("user-" + UUID.randomUUID())
              .setTotalAmountCents(1000L)
              .setStatus(PaymentStatus.PENDING);
    }

    private static Stream<Arguments> checkConstraintCases() {
        return Stream.of(
              Arguments.of(
                    "invalid payment status via native INSERT",
                    (Consumer<EntityManager>) em -> em.createNativeQuery("""
                            INSERT INTO payments (id, order_id, payment_intent_id, user_id, total_amount_cents, status, failure_reason, version, created_at, updated_at)
                            VALUES (gen_random_uuid(), gen_random_uuid(), :piId, 'user1', 1000, 'INVALID_STATUS', NULL, 0, now(), now())
                        """).setParameter("piId", "pi_" + UUID.randomUUID())
                          .executeUpdate(),
                    "chk_payments_status"
              ),
              Arguments.of(
                    "negative total_amount_cents via native INSERT",
                    (Consumer<EntityManager>) em -> em.createNativeQuery("""
                            INSERT INTO payments (id, order_id, payment_intent_id, user_id, total_amount_cents, status, failure_reason, version, created_at, updated_at)
                            VALUES (gen_random_uuid(), gen_random_uuid(), :piId, 'user1', -100, 'PENDING', NULL, 0, now(), now())
                        """).setParameter("piId", "pi_" + UUID.randomUUID())
                          .executeUpdate(),
                    "chk_payments_total_amount_cents_non_negative"
              ),
              Arguments.of(
                    "NULL order_id via native INSERT",
                    (Consumer<EntityManager>) em -> em.createNativeQuery("""
                            INSERT INTO payments (id, order_id, payment_intent_id, user_id, total_amount_cents, status, failure_reason, version, created_at, updated_at)
                            VALUES (gen_random_uuid(), NULL, :piId, 'user1', 1000, 'PENDING', NULL, 0, now(), now())
                        """).setParameter("piId", "pi_" + UUID.randomUUID())
                          .executeUpdate(),
                    "order_id"
              ),
              Arguments.of(
                    "NULL user_id via native INSERT",
                    (Consumer<EntityManager>) em -> em.createNativeQuery("""
                            INSERT INTO payments (id, order_id, payment_intent_id, user_id, total_amount_cents, status, failure_reason, version, created_at, updated_at)
                            VALUES (gen_random_uuid(), gen_random_uuid(), :piId, NULL, 1000, 'PENDING', NULL, 0, now(), now())
                        """).setParameter("piId", "pi_" + UUID.randomUUID())
                          .executeUpdate(),
                    "user_id"
              ),
              Arguments.of(
                    "NULL status via native INSERT",
                    (Consumer<EntityManager>) em -> em.createNativeQuery("""
                            INSERT INTO payments (id, order_id, payment_intent_id, user_id, total_amount_cents, status, failure_reason, version, created_at, updated_at)
                            VALUES (gen_random_uuid(), gen_random_uuid(), :piId, 'user1', 1000, NULL, NULL, 0, now(), now())
                        """).setParameter("piId", "pi_" + UUID.randomUUID())
                          .executeUpdate(),
                    "status"
              )
        );
    }

    @Nested
    @DisplayName("Valid Boundary Conditions")
    class ValidBoundaries {

        @Test
        @DisplayName("total_amount_cents = 0 (zero-value payment) is valid at DB level")
        void totalAmountCents_zero_isValid() {
            Payment payment = newPayment().setTotalAmountCents(0L);
            assertThatCode(() -> paymentRepository.saveAndFlush(payment)).doesNotThrowAnyException();

            Payment reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
            assertThat(reloaded.getTotalAmountCents()).isZero();
        }

        @Test
        @DisplayName("Optional payment_intent_id and failure_reason remain NULL without violating table constraints")
        void nullableFields_stayNull_isValid() {
            Payment payment = newPayment()
                  .setPaymentIntentId(null)
                  .setFailureReason(null);
            assertThatCode(() -> paymentRepository.saveAndFlush(payment)).doesNotThrowAnyException();

            Payment reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
            assertThat(reloaded.getPaymentIntentId()).isNull();
            assertThat(reloaded.getFailureReason()).isNull();
        }
    }

    @ParameterizedTest(name = "[{index}] DB rejects {0}")
    @MethodSource("checkConstraintCases")
    void dbCheckConstraints_areEnforced(String description,
                                        Consumer<EntityManager> queryRunner,
                                        String expectedConstraintSnippet) {
        assertThatThrownBy(() -> queryRunner.accept(entityManager))
              .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class)
              .satisfies(e -> assertThat(NestedExceptionUtils.getRootCause(e))
                    .hasMessageContaining(expectedConstraintSnippet)
              );
    }

    @Test
    @DisplayName("duplicate order_id triggers uq_payments_order_id unique constraint")
    void duplicateOrderId_rejectedByUniqueConstraint() {
        UUID sharedOrderId = UUID.randomUUID();
        paymentRepository.saveAndFlush(newPayment().setOrderId(sharedOrderId));

        Payment duplicatePayment = newPayment().setOrderId(sharedOrderId);

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(duplicatePayment))
              .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class)
              .satisfies(e -> assertThat(NestedExceptionUtils.getRootCause(e))
                    .hasMessageContaining("uq_payments_order_id")
              );
    }

    @Test
    @DisplayName("duplicate payment_intent_id triggers uq_payments_payment_intent_id unique constraint")
    void duplicatePaymentIntentId_rejectedByUniqueConstraint() {
        String sharedIntentId = "pi_shared_" + UUID.randomUUID();
        paymentRepository.saveAndFlush(newPayment().setPaymentIntentId(sharedIntentId));

        Payment duplicatePayment = newPayment().setPaymentIntentId(sharedIntentId);

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(duplicatePayment))
              .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class)
              .satisfies(e -> assertThat(NestedExceptionUtils.getRootCause(e))
                    .hasMessageContaining("uq_payments_payment_intent_id")
              );
    }

    @Test
    @DisplayName("duplicate primary key triggers PK constraint")
    void duplicatePrimaryKey_rejectedByPkConstraint() {
        UUID existingId = paymentRepository.saveAndFlush(newPayment()).getId();

        Query query = entityManager.createNativeQuery("""
            INSERT INTO payments (id, order_id, payment_intent_id, user_id, total_amount_cents, status, failure_reason, version, created_at, updated_at)
            VALUES (:id, :orderId, :piId, 'user2', 1000, 'PENDING', NULL, 0, now(), now())
        """).setParameter("id", existingId)
              .setParameter("orderId", UUID.randomUUID())
              .setParameter("piId", "pi_" + UUID.randomUUID());

        assertThatThrownBy(query::executeUpdate)
              .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class)
              .satisfies(e -> assertThat(NestedExceptionUtils.getRootCause(e))
                    .hasMessageContaining("payments_pkey")
              );
    }
}