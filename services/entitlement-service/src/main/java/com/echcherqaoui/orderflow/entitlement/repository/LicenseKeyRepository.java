package com.echcherqaoui.orderflow.entitlement.repository;

import com.echcherqaoui.orderflow.entitlement.model.LicenseKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LicenseKeyRepository extends JpaRepository<LicenseKey, Long> {
    // Returns a list of key codes that already exist in the database from the given list
    @Query("SELECT l.keyCode FROM LicenseKey l WHERE l.keyCode IN :keyCodes")
    List<String> findExistingKeyCodes(@Param("keyCodes") List<String> keyCodes);
}