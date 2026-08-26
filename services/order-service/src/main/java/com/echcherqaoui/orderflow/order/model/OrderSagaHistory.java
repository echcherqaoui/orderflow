package com.echcherqaoui.orderflow.order.model;

import com.echcherqaoui.orderflow.order.model.enums.SagaStep;
import com.echcherqaoui.orderflow.order.model.enums.SagaStepStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable audit log tracking execution steps and state transitions for the Order Saga.
 * Inserted atomically alongside Order state changes within the same database transaction.
 */
@Entity
@Table(name = "order_saga_history", indexes = {
      @Index(name = "idx_saga_history_order_id", columnList = "order_id, id")
})
@Getter
@Setter
@NoArgsConstructor
@Accessors(chain = true)
public class OrderSagaHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false, updatable = false)
    private Long id;

    @Column(nullable = false, updatable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private SagaStep step;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private SagaStepStatus status;

    @Column(updatable = false)
    private String triggerEvent;

    @Column(updatable = false)
    private String triggerEventId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> metadata;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
