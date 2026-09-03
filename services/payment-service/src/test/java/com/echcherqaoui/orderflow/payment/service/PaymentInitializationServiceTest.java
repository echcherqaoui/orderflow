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
import org.springframework.dao.DataIntegrityViolationException;

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
    class Validation {

        @Test
        @DisplayName("null orderId throws NullPointerException")
        void initializePayment_nullOrderId_throwsNullPointerException() {
            assertThatThrownBy(() -> paymentInitializationService.initializePayment(null, userId, totalAmountCents, triggerEventId))
                  .isInstanceOf(NullPointerException.class)
                  .hasMessage("orderId must not be null");
        }

        @Test
        @DisplayName("null userId throws NullPointerException")
        void initializePayment_nullUserId_throwsNullPointerException() {
            assertThatThrownBy(() -> paymentInitializationService.initializePayment(orderId, null, totalAmountCents, triggerEventId))
                  .isInstanceOf(NullPointerException.class)
                  .hasMessage("userId must not be null");
        }

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
        @DisplayName("transactional writer duplicate order constraint intercepted gracefully")
        void initializePayment_writerDataIntegrityViolationDuplicateOrder_handlesGracefully() {
            given(paymentRepository.existsByOrderId(orderId)).willReturn(false, true);
            given(paymentGateway.createIntent(orderId.toString(), totalAmountCents)).willReturn(pspResponse);
            willThrow(new DataIntegrityViolationException("Duplicate key order_id"))
                  .given(transactionalWriter)
                  .savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);

            paymentInitializationService.initializePayment(orderId, userId, totalAmountCents, triggerEventId);

            then(transactionalWriter).should().savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);
        }

        @Test
        @DisplayName("transactional writer non-duplicate constraint rethrows exception")
        void initializePayment_writerDataIntegrityViolationUnrelated_rethrowsException() {
            DataIntegrityViolationException ex = new DataIntegrityViolationException("FK constraint failure");

            given(paymentRepository.existsByOrderId(orderId)).willReturn(false);
            given(paymentGateway.createIntent(orderId.toString(), totalAmountCents)).willReturn(pspResponse);
            willThrow(ex)
                  .given(transactionalWriter)
                  .savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);

            assertThatThrownBy(() -> paymentInitializationService.initializePayment(orderId, userId, totalAmountCents, triggerEventId))
                  .isSameAs(ex);
        }


        @Test
        @DisplayName("payment gateway failure triggers failure payment persistence and outbox event")
        void initializePayment_gatewayFails_persistsFailurePaymentAndOutbox() {
            RuntimeException pspException = new RuntimeException("PSP connection failure");

            given(paymentRepository.existsByOrderId(orderId)).willReturn(false);
            given(paymentGateway.createIntent(orderId.toString(), totalAmountCents)).willThrow(pspException);

            paymentInitializationService.initializePayment(orderId, userId, totalAmountCents, triggerEventId);

            then(paymentRepository).should().existsByOrderId(orderId);
            then(paymentGateway).should().createIntent(orderId.toString(), totalAmountCents);
            then(transactionalWriter).should().saveFailurePaymentAndOutbox(
                  orderId,
                  userId,
                  totalAmountCents,
                  triggerEventId,
                  "PSP connection failure"
            );
        }

        @Test
        @DisplayName("failure writer duplicate order constraint intercepted gracefully")
        void initializePayment_failureWriterDuplicateOrder_handlesGracefully() {
            RuntimeException pspException = new RuntimeException("PSP timeout");

            given(paymentRepository.existsByOrderId(orderId)).willReturn(false, true);
            given(paymentGateway.createIntent(orderId.toString(), totalAmountCents)).willThrow(pspException);
            willThrow(new DataIntegrityViolationException("Duplicate key order_id"))
                  .given(transactionalWriter)
                  .saveFailurePaymentAndOutbox(orderId, userId, totalAmountCents, triggerEventId, "PSP timeout");

            paymentInitializationService.initializePayment(orderId, userId, totalAmountCents, triggerEventId);

            then(transactionalWriter).should().saveFailurePaymentAndOutbox(orderId, userId, totalAmountCents, triggerEventId, "PSP timeout");
        }

        @Test
        @DisplayName("failure writer non-duplicate constraint rethrows exception")
        void initializePayment_failureWriterUnrelatedConstraint_rethrowsException() {
            RuntimeException pspException = new RuntimeException("PSP timeout");
            DataIntegrityViolationException dbEx = new DataIntegrityViolationException("FK failure");

            given(paymentRepository.existsByOrderId(orderId)).willReturn(false);
            given(paymentGateway.createIntent(orderId.toString(), totalAmountCents)).willThrow(pspException);
            willThrow(dbEx)
                  .given(transactionalWriter)
                  .saveFailurePaymentAndOutbox(orderId, userId, totalAmountCents, triggerEventId, "PSP timeout");

            assertThatThrownBy(() -> paymentInitializationService.initializePayment(orderId, userId, totalAmountCents, triggerEventId))
                  .isSameAs(dbEx);
        }
    }
}