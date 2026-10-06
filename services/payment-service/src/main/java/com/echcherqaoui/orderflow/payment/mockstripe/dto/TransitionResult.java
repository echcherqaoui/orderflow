package com.echcherqaoui.orderflow.payment.mockstripe.dto;

import com.echcherqaoui.orderflow.payment.mockstripe.model.MockPaymentIntent;

public record TransitionResult(MockPaymentIntent intent, boolean applied) {}