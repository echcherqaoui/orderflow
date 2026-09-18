package com.echcherqaoui.orderflow.payment.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

@Entity
@Table(name = "processed_webhook_events")
@Getter
@Setter
@Accessors(chain = true)
public class ProcessedWebhookEvent implements Persistable<String> {

    @Id
    private String eventId;

    @Column(nullable = false, length = 100)
    private String eventType;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant processedAt;

    @Transient
    private boolean isNew = true;

    @Override
    public String getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public ProcessedWebhookEvent markNotNew() {
        this.isNew = false;
        return this;
    }
}