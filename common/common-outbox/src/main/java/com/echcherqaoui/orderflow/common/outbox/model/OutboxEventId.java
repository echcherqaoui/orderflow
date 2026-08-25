package com.echcherqaoui.orderflow.common.outbox.model;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEventId implements Serializable {
    
    private UUID id;
    private Instant createdAt;
}