package com.echcherqaoui.orderflow.payment.model;

import com.echcherqaoui.orderflow.payment.domain.PaymentAttempt;
import com.echcherqaoui.orderflow.payment.support.WithPostgres;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
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
class PaymentAttemptEntityConstraintIT implements WithPostgres {

    @Autowired
    private EntityManager entityManager;

    private Payment persistentPayment;

    @BeforeEach
    void setUp() {
        persistentPayment = new Payment()
              .setOrderId(UUID.randomUUID())
              .setPaymentIntentId("pi_" + UUID.randomUUID())
              .setUserId("user-" + UUID.randomUUID())
              .setTotalAmountCents(1000L)
              .setStatus(PaymentStatus.PENDING);

        entityManager.persist(persistentPayment);
        entityManager.flush();
    }

    private PaymentAttempt newPaymentAttempt(PaymentAttemptStatus status) {
        return new PaymentAttempt()
              .setPayment(persistentPayment)
              .setStatus(status)
              .setErrorCode(null);
    }

    private static Stream<Arguments> checkConstraintCases() {
        return Stream.of(
              Arguments.of(
                    "invalid status via native INSERT",
                    (Consumer<EntityManager>) em -> em.createNativeQuery("""
                            INSERT INTO payment_attempts (id, payment_id, status, error_code, created_at)
                            VALUES (gen_random_uuid(), :paymentId, 'INVALID_STATUS', NULL, now())
                        """).setParameter("paymentId", UUID.randomUUID())
                          .executeUpdate(),
                    "chk_payment_attempts_status"
              ),
              Arguments.of(
                    "NULL payment_id via native INSERT",
                    (Consumer<EntityManager>) em -> em.createNativeQuery("""
                            INSERT INTO payment_attempts (id, payment_id, status, error_code, created_at)
                            VALUES (gen_random_uuid(), NULL, 'FAILED', NULL, now())
                        """).executeUpdate(),
                    "payment_id"
              ),
              Arguments.of(
                    "NULL status via native INSERT",
                    (Consumer<EntityManager>) em -> em.createNativeQuery("""
                            INSERT INTO payment_attempts (id, payment_id, status, error_code, created_at)
                            VALUES (gen_random_uuid(), :paymentId, NULL, NULL, now())
                        """).setParameter("paymentId", UUID.randomUUID())
                          .executeUpdate(),
                    "status"
              )
        );
    }

    @Nested
    @DisplayName("Valid Boundary Conditions")
    class ValidBoundaries {

        @Test
        @DisplayName("Optional error_code remains NULL without violating table constraints")
        void nullableFields_stayNull_isValid() {
            PaymentAttempt attempt = newPaymentAttempt(PaymentAttemptStatus.SUCCESS).setErrorCode(null);
            assertThatCode(() -> {
                entityManager.persist(attempt);
                entityManager.flush();
            }).doesNotThrowAnyException();

            PaymentAttempt reloaded = entityManager.find(PaymentAttempt.class, attempt.getId());
            assertThat(reloaded.getErrorCode()).isNull();
        }

        @Test
        @DisplayName("Populated error_code is persisted and retrieved successfully")
        void errorCode_populated_isValid() {
            PaymentAttempt attempt = newPaymentAttempt(PaymentAttemptStatus.FAILED).setErrorCode("PAYMENT_DECLINED");
            assertThatCode(() -> {
                entityManager.persist(attempt);
                entityManager.flush();
            }).doesNotThrowAnyException();

            PaymentAttempt reloaded = entityManager.find(PaymentAttempt.class, attempt.getId());
            assertThat(reloaded.getErrorCode()).isEqualTo("PAYMENT_DECLINED");
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
    @DisplayName("non-existent payment_id triggers fk_payment_attempts_payment foreign key constraint")
    void nonExistentPaymentId_rejectedByForeignKeyConstraint() {
        UUID nonExistentPaymentId = UUID.randomUUID();

        Query query = entityManager.createNativeQuery("""
            INSERT INTO payment_attempts (id, payment_id, status, error_code, created_at)
            VALUES (gen_random_uuid(), :paymentId, 'FAILED', NULL, now())
        """).setParameter("paymentId", nonExistentPaymentId);

        assertThatThrownBy(query::executeUpdate)
              .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class)
              .satisfies(e -> assertThat(NestedExceptionUtils.getRootCause(e))
                    .hasMessageContaining("fk_payment_attempts_payment")
              );
    }

    @Test
    @DisplayName("duplicate primary key triggers PK constraint")
    void duplicatePrimaryKey_rejectedByPkConstraint() {
        PaymentAttempt attempt = newPaymentAttempt(PaymentAttemptStatus.FAILED);
        entityManager.persist(attempt);
        entityManager.flush();

        Query query = entityManager.createNativeQuery("""
            INSERT INTO payment_attempts (id, payment_id, status, error_code, created_at)
            VALUES (:id, :paymentId, 'FAILED', 'INSUFFICIENT_FUNDS', now())
        """).setParameter("id", attempt.getId())
              .setParameter("paymentId", persistentPayment.getId());

        assertThatThrownBy(query::executeUpdate)
              .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class)
              .satisfies(e -> assertThat(NestedExceptionUtils.getRootCause(e))
                    .hasMessageContaining("payment_attempts_pkey")
              );
    }
}