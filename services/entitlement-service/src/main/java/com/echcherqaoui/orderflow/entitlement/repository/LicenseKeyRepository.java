package com.echcherqaoui.orderflow.entitlement.repository;

import com.echcherqaoui.orderflow.entitlement.model.LicenseKey;
import com.echcherqaoui.orderflow.entitlement.model.LicenseKeyStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LicenseKeyRepository extends JpaRepository<LicenseKey, Long> {
    // Returns a list of key codes that already exist in the database from the given list
    @Query("SELECT l.keyCode FROM LicenseKey l WHERE l.keyCode IN :keyCodes")
    List<String> findExistingKeyCodes(@Param("keyCodes") List<String> keyCodes);

    @Modifying
    @Query("""
        UPDATE LicenseKey k SET k.status = :newStatus
            WHERE k.itemName = :itemName AND k.status = :oldStatus
    """)
    int updateStatusForProduct(@Param("itemName") String itemName,
                               @Param("oldStatus") LicenseKeyStatus oldStatus,
                               @Param("newStatus") LicenseKeyStatus newStatus);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")) // SKIP LOCKED
    Optional<LicenseKey> findFirstByItemIdAndStatusOrderByIdAsc(UUID itemId, LicenseKeyStatus status);
}