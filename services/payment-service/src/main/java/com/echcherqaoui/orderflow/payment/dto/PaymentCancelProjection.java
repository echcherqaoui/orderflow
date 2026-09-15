package com.echcherqaoui.orderflow.payment.dto;

import com.echcherqaoui.orderflow.payment.model.PaymentStatus;

public record PaymentCancelProjection(
      PaymentStatus status,
      String paymentIntentId
) {}