package com.echcherqaoui.orderflow.payment.service;

import com.echcherqaoui.orderflow.payment.dto.CreatePaymentIntentResponse;
import com.echcherqaoui.orderflow.payment.gateway.PaymentGateway;
import com.echcherqaoui.orderflow.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PaymentInitializationServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private PaymentTransactionalWriter transactionalWriter;

    @InjectMocks
    private PaymentInitializationService paymentInitializationService;

    private final UUID orderId = UUID.randomUUID();
    private final String userId = "user-789";
    private final long totalAmountCents = 12500L;
    private final String triggerEventId = "evt-" + UUID.randomUUID();

    private CreatePaymentIntentResponse pspResponse;

    @BeforeEach
    void setUp() {
        pspResponse = new CreatePaymentIntentResponse("pi_123456", "requires_payment_method");
    }

    @Nested
    @DisplayName("initializePayment()")
    class InitializePayment {

        @Test
        @DisplayName("payment already exists returns early without invoking gateway or transactional writer")
        void initializePayment_paymentAlreadyExists_returnsEarlyWithoutInvokingGatewayOrWriter() {
            given(paymentRepository.existsByOrderId(orderId)).willReturn(true);

            paymentInitializationService.initializePayment(orderId, userId, totalAmountCents, triggerEventId);

            then(paymentRepository).should().existsByOrderId(orderId);
            verifyNoInteractions(paymentGateway, transactionalWriter);
        }

        @Test
        @DisplayName("successful execution creates intent and persists payment state atomically with outbox event")
        void initializePayment_success_createsIntentAndPersistsStateWithOutbox() {
            given(paymentRepository.existsByOrderId(orderId)).willReturn(false);
            given(paymentGateway.createIntent(orderId.toString(), totalAmountCents)).willReturn(pspResponse);

            paymentInitializationService.initializePayment(orderId, userId, totalAmountCents, triggerEventId);

            then(paymentRepository).should().existsByOrderId(orderId);
            then(paymentGateway).should().createIntent(orderId.toString(), totalAmountCents);
            then(transactionalWriter).should().savePaymentAndOutbox(
                  orderId,
                  userId,
                  totalAmountCents,
                  pspResponse,
                  triggerEventId
            );
        }

        @Test
        @DisplayName("payment gateway failure propagates exception and aborts transactional write")
        void initializePayment_gatewayFails_propagatesExceptionAndAbortsWriter() {
            RuntimeException pspException = new RuntimeException("PSP connection failure");

            given(paymentRepository.existsByOrderId(orderId)).willReturn(false);
            given(paymentGateway.createIntent(orderId.toString(), totalAmountCents)).willThrow(pspException);

            assertThatThrownBy(() -> paymentInitializationService.initializePayment(orderId, userId, totalAmountCents, triggerEventId))
                  .isSameAs(pspException);

            then(paymentRepository).should().existsByOrderId(orderId);
            then(paymentGateway).should().createIntent(orderId.toString(), totalAmountCents);
            verifyNoInteractions(transactionalWriter);
        }

        @Test
        @DisplayName("transactional writer failure propagates exception after gateway intent creation")
        void initializePayment_transactionalWriterFails_propagatesException() {
            RuntimeException dbException = new RuntimeException("Database save error");

            given(paymentRepository.existsByOrderId(orderId)).willReturn(false);
            given(paymentGateway.createIntent(orderId.toString(), totalAmountCents)).willReturn(pspResponse);

            willThrow(dbException)
                  .given(transactionalWriter)
                  .savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);

            assertThatThrownBy(() -> paymentInitializationService.initializePayment(orderId, userId, totalAmountCents, triggerEventId))
                  .isSameAs(dbException);

            then(paymentRepository).should().existsByOrderId(orderId);
            then(paymentGateway).should().createIntent(orderId.toString(), totalAmountCents);
            then(transactionalWriter).should().savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);
        }
    }
}