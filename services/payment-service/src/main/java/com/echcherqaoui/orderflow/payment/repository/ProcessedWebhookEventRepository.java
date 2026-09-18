package com.echcherqaoui.orderflow.payment.repository;

import com.echcherqaoui.orderflow.payment.model.ProcessedWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedWebhookEventRepository extends JpaRepository<ProcessedWebhookEvent, String> {
}