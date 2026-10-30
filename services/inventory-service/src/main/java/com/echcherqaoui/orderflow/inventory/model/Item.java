package com.echcherqaoui.orderflow.inventory.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "items")
@Getter
@Setter
@Accessors(chain = true)
public class Item {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Min(0)
    @Column(name = "price_cents", nullable = false)
    private Long priceCents;

    @Min(1)
    @Column(name = "total_early_access_units", nullable = false)
    private Integer totalEarlyAccessUnits;

    @Min(0)
    @Column(name = "remaining_units", nullable = false)
    private Integer remainingUnits;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void addStock(int quantity) {
        this.totalEarlyAccessUnits += quantity;
        this.remainingUnits += quantity;
    }

    public boolean hasPrice(long priceCents) {
        return this.priceCents.equals(priceCents);
    }
}
