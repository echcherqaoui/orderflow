package com.echcherqaoui.orderflow.entitlement.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

import static com.echcherqaoui.orderflow.entitlement.model.LicenseKeyStatus.PENDING_PROVISION;

@Entity
@Table(name = "license_keys")
@Setter
@Getter
@Accessors(chain = true)
public class LicenseKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID itemId;

    @Column(nullable = false)
    private String itemName;

    @Column(nullable = false, unique = true)
    private String keyCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LicenseKeyStatus status = PENDING_PROVISION;

    @Column( nullable = false)
    private Long priceCents;

    private UUID orderId;

    private String userId;

    private Instant assignedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;
}