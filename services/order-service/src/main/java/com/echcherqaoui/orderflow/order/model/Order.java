package com.echcherqaoui.orderflow.order.model;

import com.echcherqaoui.orderflow.order.model.enums.CancellationReason;
import com.echcherqaoui.orderflow.order.model.enums.CompensationOutcome;
import com.echcherqaoui.orderflow.order.model.enums.OrderStatus;
import com.echcherqaoui.orderflow.order.model.enums.SagaStep;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.echcherqaoui.orderflow.order.model.enums.OrderStatus.PENDING;
import static jakarta.persistence.EnumType.STRING;

@Entity
@Table(name = "orders", indexes = {
      @Index(name = "idx_orders_user_id", columnList = "user_id")
})
@Getter
@Setter
@Accessors(chain = true)
public class Order {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    // userEmail -> userId rename: field still holds an email value today,
    // but the name reflects its role (identifier) rather than the current format.
    @Column(nullable = false, updatable = false)
    private String userId;

    // Frontend-generated cart identifier, used
    // to correlate with Inventory Service's reservation.
    @Column(nullable = false, updatable = false, unique = true)
    private String cartId;

    @Enumerated(STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status = PENDING;

    @Enumerated(STRING)
    @Column(nullable = false, length = 64)
    private SagaStep currentSagaStep;

    @Enumerated(STRING)
    @Column(nullable = false, length = 64)
    private CancellationReason cancellationReason = CancellationReason.NONE;

    @Column(nullable = false, updatable = false)
    private long totalAmountCents;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    // Set only once, when PaymentInitiatedEvent is first consumed.
    private String paymentIntentId;

    // Populated during the entitlement-failure parallel compensation branch
    // (RefundPaymentCommand + ReleaseInventoryCommand fired together).
    @Enumerated(STRING)
    @Column(length = 32)
    private CompensationOutcome refundStatus;

    @Enumerated(STRING)
    @Column(length = 32)
    private CompensationOutcome inventoryReleaseStatus;

    // Diagram explicitly calls for @Version on the parallel-aggregation step
    // (compensation events can arrive concurrently from two different consumer
    // threads) — optimistic locking prevents a lost update between them.
    @Version
    @Column(nullable = false)
    private long version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public void addItem(@NonNull OrderItem item) {
        item.setOrder(this);
        items.add(item);
    }
}
