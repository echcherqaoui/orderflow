package com.echcherqaoui.orderflow.inventory.repository;

import com.echcherqaoui.orderflow.inventory.model.InventoryReservation;
import com.echcherqaoui.orderflow.inventory.model.ReservationStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<InventoryReservation> findByCartIdAndStatus(String cartId, ReservationStatus status);

    @Query("""
            FROM InventoryReservation r
                WHERE r.status = :status AND r.expiresAt < :now
          """)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
        // -2 = SKIP LOCKED in Postgres/Hibernate
    List<InventoryReservation> findExpiredPendingForUpdate(@Param("status") ReservationStatus status,
                                                           @Param("now") Instant now,
                                                           Pageable pageable);

    @Modifying
    @Query("""
            UPDATE InventoryReservation r
            SET r.expiresAt = :newExpiry
                WHERE r.cartId = :cartId AND r.status = :status
          """)
    void updateExpiresAtByCartId(@Param("cartId") String cartId,
                                 @Param("newExpiry") Instant newExpiry,
                                 @Param("status") ReservationStatus status);

    @Modifying
    @Query("""
            UPDATE InventoryReservation r
            SET r.status = :status
                WHERE r.id IN :ids
          """)
    void updateStatusByIds(@Param("ids") List<UUID> ids,
                           @Param("status") ReservationStatus status);

    @Modifying
    @Query("""
            UPDATE InventoryReservation r
            SET r.expiresAt = :newExpiresAt, r.orderId = :orderId
                WHERE r.cartId = :cartId
                    AND r.expiresAt > :now
                    AND r.status = 'PENDING'
          """)
    int extendReservationAndSetOrderId(@Param("newExpiresAt") Instant newExpiresAt,
                                       @Param("orderId") UUID orderId,
                                       @Param("cartId") String cartId,
                                       @Param("now") Instant now);
}
