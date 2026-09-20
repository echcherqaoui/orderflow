package com.echcherqaoui.orderflow.payment.mockstripe.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfirmPaymentIntentRequest(@NotBlank(message = "client_secret is required")
                                          String clientSecret,

                                          @NotBlank(message = "payment_method is required")
                                          String paymentMethod) {}