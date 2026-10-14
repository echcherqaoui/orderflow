package com.echcherqaoui.orderflow.entitlement.model;

import com.echcherqaoui.orderflow.entitlement.repository.LicenseKeyRepository;
import com.echcherqaoui.orderflow.entitlement.support.WithPostgres;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
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
class LicenseKeyEntityConstraintIT implements WithPostgres {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private LicenseKeyRepository licenseKeyRepository;

    private LicenseKey newLicenseKey(String keyCode) {
        return new LicenseKey()
              .setProductId("prod-" + UUID.randomUUID())
              .setKeyCode(keyCode)
              .setPriceCents(1999L);
    }

    private static Stream<Arguments> checkConstraintCases() {
        return Stream.of(
              Arguments.of(
                    "price_cents < 0 via native INSERT",
                    (Consumer<EntityManager>) em -> em.createNativeQuery("""
                        INSERT INTO license_keys (product_id, key_code, status, price_cents, version, created_at, updated_at)
                        VALUES (:productId, :keyCode, 'AVAILABLE', -1, 0, now(), now())
                    """).setParameter("productId", "prod-" + UUID.randomUUID())
                          .setParameter("keyCode", "KEY-BAD-PRICE-" + UUID.randomUUID())
                          .executeUpdate(),
                    "chk_license_keys_price_non_negative"
              ),
              Arguments.of(
                    "invalid status via native INSERT",
                    (Consumer<EntityManager>) em -> em.createNativeQuery("""
                        INSERT INTO license_keys (product_id, key_code, status, price_cents, version, created_at, updated_at)
                        VALUES (:productId, :keyCode, 'INVALID_STATUS', 1000, 0, now(), now())
                    """).setParameter("productId", "prod-" + UUID.randomUUID())
                          .setParameter("keyCode", "KEY-BAD-STATUS-" + UUID.randomUUID())
                          .executeUpdate(),
                    "chk_license_keys_status"
              )
        );
    }

    @Nested
    @DisplayName("Valid Boundary Conditions")
    class ValidBoundaries {

        @Test
        @DisplayName("price_cents = 0 (lower bound) is valid at DB level")
        void priceCents_lowerBoundary_isValid() {
            LicenseKey key = newLicenseKey("KEY-ZERO-" + UUID.randomUUID()).setPriceCents(0L);
            assertThatCode(() -> licenseKeyRepository.saveAndFlush(key)).doesNotThrowAnyException();
        }
    }

    @ParameterizedTest(name = "[{index}] DB rejects {0}")
    @MethodSource("checkConstraintCases")
    void dbCheckConstraints_areEnforced(String description,
                                        Consumer<EntityManager> queryRunner,
                                        String expectedConstraintName) {
        assertThatThrownBy(() -> queryRunner.accept(entityManager))
              .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class)
              .satisfies(e -> assertThat(NestedExceptionUtils.getRootCause(e))
                    .hasMessageContaining(expectedConstraintName)
              );
    }


    @Test
    @DisplayName("duplicate key_code triggers unique constraint violation")
    void duplicateKeyCode_rejectedByUniqueConstraint() {
        String sharedCode = "KEY-DUPLICATE-" + UUID.randomUUID();
        licenseKeyRepository.saveAndFlush(newLicenseKey(sharedCode));

        LicenseKey duplicate = newLicenseKey(sharedCode);

        assertThatThrownBy(() -> licenseKeyRepository.saveAndFlush(duplicate))
              .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class)
              .satisfies(e -> assertThat(NestedExceptionUtils.getRootCause(e))
                    .hasMessageContaining("uq_license_keys_key_code")
              );
    }
}