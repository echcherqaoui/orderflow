package com.echcherqaoui.orderflow.payment.service;

import com.echcherqaoui.orderflow.payment.gateway.CreatePaymentIntentResponse;
import com.echcherqaoui.orderflow.payment.dto.PaymentCancelProjection;
import com.echcherqaoui.orderflow.payment.gateway.PaymentGatewayTransientException;
import com.echcherqaoui.orderflow.payment.gateway.PaymentGateway;
import com.echcherqaoui.orderflow.payment.model.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private PaymentPersistenceService persistenceService;

    @InjectMocks
    private PaymentService paymentService;

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
        @DisplayName("null orderId throws NullPointerException")
        void nullOrderId_throwsNullPointerException() {
            assertThatThrownBy(() -> paymentService.initializePayment(null, userId, totalAmountCents, triggerEventId))
                  .isInstanceOf(NullPointerException.class)
                  .hasMessageContaining("orderId");
        }

        @Test
        @DisplayName("null userId throws NullPointerException")
        void nullUserId_throwsNullPointerException() {
            assertThatThrownBy(() -> paymentService.initializePayment(orderId, null, totalAmountCents, triggerEventId))
                  .isInstanceOf(NullPointerException.class)
                  .hasMessageContaining("userId");
        }

        @Test
        @DisplayName("payment already exists returns early without invoking gateway or persistence")
        void paymentAlreadyExists_returnsEarly() {
            given(persistenceService.existsByOrderId(orderId)).willReturn(true);

            paymentService.initializePayment(orderId, userId, totalAmountCents, triggerEventId);

            then(persistenceService).should().existsByOrderId(orderId);
            verifyNoInteractions(paymentGateway);
            then(persistenceService).shouldHaveNoMoreInteractions();
        }

        @Test
        @DisplayName("successful execution creates intent and persists payment state with outbox event")
        void success_createsIntentAndPersistsStateWithOutbox() {
            given(persistenceService.existsByOrderId(orderId)).willReturn(false);
            given(paymentGateway.createIntent(orderId.toString(), totalAmountCents)).willReturn(pspResponse);

            paymentService.initializePayment(orderId, userId, totalAmountCents, triggerEventId);

            then(persistenceService).should().existsByOrderId(orderId);
            then(paymentGateway).should().createIntent(orderId.toString(), totalAmountCents);
            then(persistenceService).should().savePaymentAndOutbox(
                  orderId,
                  userId,
                  totalAmountCents,
                  pspResponse,
                  triggerEventId
            );
        }

        @Test
        @DisplayName("transient PSP exception persists failure with PSP_PAYMENT_DECLINED reason")
        void transientPspException_persistsFailureWithDeclinedReason() {
            PaymentGatewayTransientException pspException = new PaymentGatewayTransientException();

            given(persistenceService.existsByOrderId(orderId)).willReturn(false);
            given(paymentGateway.createIntent(orderId.toString(), totalAmountCents)).willThrow(pspException);

            paymentService.initializePayment(orderId, userId, totalAmountCents, triggerEventId);

            then(persistenceService).should().saveFailurePaymentAndOutbox(
                  orderId,
                  userId,
                  totalAmountCents,
                  triggerEventId,
                  "PSP_PAYMENT_DECLINED"
            );
        }

        @Test
        @DisplayName("unhandled PSP exception persists failure with PSP_GATEWAY_UNAVAILABLE reason")
        void unhandledPspException_persistsFailureWithUnavailableReason() {
            RuntimeException pspException = new RuntimeException("PSP connection timeout");

            given(persistenceService.existsByOrderId(orderId)).willReturn(false);
            given(paymentGateway.createIntent(orderId.toString(), totalAmountCents)).willThrow(pspException);

            paymentService.initializePayment(orderId, userId, totalAmountCents, triggerEventId);

            then(persistenceService).should().saveFailurePaymentAndOutbox(
                  orderId,
                  userId,
                  totalAmountCents,
                  triggerEventId,
                  "PSP_GATEWAY_UNAVAILABLE"
            );
        }

        @Test
        @DisplayName("writer duplicate order constraint intercepted gracefully if order exists")
        void writerDataIntegrityViolationDuplicateOrder_handlesGracefully() {
            given(persistenceService.existsByOrderId(orderId)).willReturn(false, true);
            given(paymentGateway.createIntent(orderId.toString(), totalAmountCents)).willReturn(pspResponse);
            willThrow(new DataIntegrityViolationException("Duplicate key order_id"))
                  .given(persistenceService)
                  .savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);

            assertThatNoException().isThrownBy(() ->
                  paymentService.initializePayment(orderId, userId, totalAmountCents, triggerEventId)
            );

            then(persistenceService).should().savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);
        }

        @Test
        @DisplayName("writer non-duplicate constraint rethrows exception")
        void writerDataIntegrityViolationUnrelated_rethrowsException() {
            DataIntegrityViolationException ex = new DataIntegrityViolationException("FK constraint failure");

            given(persistenceService.existsByOrderId(orderId)).willReturn(false);
            given(paymentGateway.createIntent(orderId.toString(), totalAmountCents)).willReturn(pspResponse);
            willThrow(ex)
                  .given(persistenceService)
                  .savePaymentAndOutbox(orderId, userId, totalAmountCents, pspResponse, triggerEventId);

            assertThatThrownBy(() -> paymentService.initializePayment(orderId, userId, totalAmountCents, triggerEventId))
                  .isSameAs(ex);
        }

        @Test
        @DisplayName("failure writer duplicate order constraint intercepted gracefully")
        void failureWriterDuplicateOrder_handlesGracefully() {
            given(persistenceService.existsByOrderId(orderId)).willReturn(false, true);
            given(paymentGateway.createIntent(orderId.toString(), totalAmountCents)).willThrow(new RuntimeException("PSP down"));
            willThrow(new DataIntegrityViolationException("Duplicate key order_id"))
                  .given(persistenceService)
                  .saveFailurePaymentAndOutbox(orderId, userId, totalAmountCents, triggerEventId, "PSP_GATEWAY_UNAVAILABLE");

            assertThatNoException().isThrownBy(() ->
                  paymentService.initializePayment(orderId, userId, totalAmountCents, triggerEventId)
            );

            then(persistenceService).should().saveFailurePaymentAndOutbox(orderId, userId, totalAmountCents, triggerEventId, "PSP_GATEWAY_UNAVAILABLE");
        }

        @Test
        @DisplayName("failure writer non-duplicate constraint rethrows exception")
        void failureWriterUnrelatedConstraint_rethrowsException() {
            DataIntegrityViolationException dbEx = new DataIntegrityViolationException("FK failure");

            given(persistenceService.existsByOrderId(orderId)).willReturn(false);
            given(paymentGateway.createIntent(orderId.toString(), totalAmountCents)).willThrow(new RuntimeException("PSP down"));
            willThrow(dbEx)
                  .given(persistenceService)
                  .saveFailurePaymentAndOutbox(orderId, userId, totalAmountCents, triggerEventId, "PSP_GATEWAY_UNAVAILABLE");

            assertThatThrownBy(() -> paymentService.initializePayment(orderId, userId, totalAmountCents, triggerEventId))
                  .isSameAs(dbEx);
        }
    }

    @Nested
    @DisplayName("cancelPayment()")
    class CancelPayment {

        @Test
        @DisplayName("null orderId throws NullPointerException")
        void nullOrderId_throwsNullPointerException() {
            assertThatThrownBy(() -> paymentService.cancelPayment(null, "pi_123", "reason", triggerEventId))
                  .isInstanceOf(NullPointerException.class)
                  .hasMessageContaining("orderId");
        }

        @Test
        @DisplayName("payment record not found returns early")
        void paymentNotFound_returnsEarly() {
            given(persistenceService.findByOrderId(orderId)).willReturn(Optional.empty());

            paymentService.cancelPayment(orderId, "pi_123", "User cancelled", triggerEventId);

            then(persistenceService).should().findByOrderId(orderId);
            verifyNoInteractions(paymentGateway);
            then(persistenceService).shouldHaveNoMoreInteractions();
        }

        @Test
        @DisplayName("payment already CANCELLED returns early")
        void alreadyCancelled_returnsEarly() {
            PaymentCancelProjection projection = mock(PaymentCancelProjection.class);
            given(projection.status()).willReturn(PaymentStatus.CANCELLED);
            given(persistenceService.findByOrderId(orderId)).willReturn(Optional.of(projection));

            paymentService.cancelPayment(orderId, "pi_123", "User cancelled", triggerEventId);

            then(persistenceService).should().findByOrderId(orderId);
            verifyNoInteractions(paymentGateway);
            then(persistenceService).shouldHaveNoMoreInteractions();
        }

        @Test
        @DisplayName("cancels intent on PSP and persists cancellation with explicit parameters")
        void cancelsIntentAndPersistsCancellation_withExplicitParams() {
            PaymentCancelProjection projection = mock(PaymentCancelProjection.class);
            given(projection.status()).willReturn(PaymentStatus.PENDING);
            given(persistenceService.findByOrderId(orderId)).willReturn(Optional.of(projection));

            paymentService.cancelPayment(orderId, "pi_custom", "Customer requested", triggerEventId);

            then(paymentGateway).should().cancelIntent("pi_custom");
            then(persistenceService).should().saveCancellationAndOutbox(
                  orderId,
                  "pi_custom",
                  "Customer requested",
                  triggerEventId
            );
        }

        @Test
        @DisplayName("falls back to projection intent ID and default reason when arguments are null")
        void cancelsIntentAndPersistsCancellation_withFallbackParams() {
            PaymentCancelProjection projection = mock(PaymentCancelProjection.class);
            given(projection.status()).willReturn(PaymentStatus.PENDING);
            given(projection.paymentIntentId()).willReturn("pi_existing");
            given(persistenceService.findByOrderId(orderId)).willReturn(Optional.of(projection));

            paymentService.cancelPayment(orderId, null, null, triggerEventId);

            then(paymentGateway).should().cancelIntent("pi_existing");
            then(persistenceService).should().saveCancellationAndOutbox(
                  orderId,
                  "pi_existing",
                  "Compensation triggered",
                  triggerEventId
            );
        }

        @Test
        @DisplayName("PSP gateway failure is logged and local DB cancellation proceeds")
        void pspFailure_proceedsWithLocalCancellation() {
            PaymentCancelProjection projection = mock(PaymentCancelProjection.class);
            given(projection.status()).willReturn(PaymentStatus.PENDING);
            given(projection.paymentIntentId()).willReturn("pi_existing");
            given(persistenceService.findByOrderId(orderId)).willReturn(Optional.of(projection));
            willThrow(new RuntimeException("PSP gateway timeout")).given(paymentGateway).cancelIntent("pi_existing");

            assertThatNoException().isThrownBy(() ->
                  paymentService.cancelPayment(orderId, null, "Order timed out", triggerEventId)
            );

            then(paymentGateway).should().cancelIntent("pi_existing");
            then(persistenceService).should().saveCancellationAndOutbox(
                  orderId,
                  "pi_existing",
                  "Order timed out",
                  triggerEventId
            );
        }
    }
}