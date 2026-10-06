package com.echcherqaoui.orderflow.payment.mockstripe.store;

import com.echcherqaoui.orderflow.payment.mockstripe.dto.TransitionResult;
import com.echcherqaoui.orderflow.payment.mockstripe.model.MockPaymentIntent;
import com.echcherqaoui.orderflow.payment.mockstripe.model.MockPaymentIntentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.echcherqaoui.orderflow.payment.mockstripe.model.MockPaymentIntentStatus.CANCELED;
import static com.echcherqaoui.orderflow.payment.mockstripe.model.MockPaymentIntentStatus.REQUIRES_PAYMENT_METHOD;
import static com.echcherqaoui.orderflow.payment.mockstripe.model.MockPaymentIntentStatus.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockPaymentIntentStoreTest {

    private MockPaymentIntentStore store;
    private static final String IDEMPOTENCY_KEY = "idem_key_123";
    private static final long AMOUNT_CENTS = 5000L;
    private static final int MAX_ALLOWED_ATTEMPTS = 3;

    @BeforeEach
    void setUp() {
        store = new MockPaymentIntentStore();
    }

    @Nested
    @DisplayName("getOrCreate()")
    class GetOrCreate {

        @Test
        @DisplayName("creates and stores new payment intent atomically given a new idempotency key")
        void getOrCreate_newKey_createsAndStoresIntent() {
            MockPaymentIntent created = store.getOrCreate(IDEMPOTENCY_KEY, AMOUNT_CENTS);

            assertThat(created).isNotNull();
            assertThat(created.idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
            assertThat(created.totalAmountCents()).isEqualTo(AMOUNT_CENTS);
            assertThat(created.status()).isEqualTo(REQUIRES_PAYMENT_METHOD);
            assertThat(created.paymentIntentId()).startsWith("pi_mock_");
            assertThat(created.clientSecret()).startsWith("secret_mock_");
            assertThat(created.attemptCount()).isZero();
            assertThat(created.lastErrorCode()).isNull();
        }

        @Test
        @DisplayName("returns existing payment intent without creating a new one when idempotency key recurs")
        void getOrCreate_existingKey_returnsSameIntent() {
            MockPaymentIntent firstCall = store.getOrCreate(IDEMPOTENCY_KEY, AMOUNT_CENTS);
            MockPaymentIntent secondCall = store.getOrCreate(IDEMPOTENCY_KEY, AMOUNT_CENTS);

            assertThat(secondCall).isSameAs(firstCall);
        }

        @Test
        @DisplayName("throws NullPointerException when idempotency key is null")
        void getOrCreate_nullIdempotencyKey_throwsNullPointerException() {
            assertThatThrownBy(() -> store.getOrCreate(null, AMOUNT_CENTS))
                  .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("recordAttempt()")
    class RecordAttempt {

        @Test
        @DisplayName("records successful attempt and transitions status to SUCCEEDED")
        void recordAttempt_success_transitionsToSucceeded() {
            MockPaymentIntent initial = store.getOrCreate(IDEMPOTENCY_KEY, AMOUNT_CENTS);

            TransitionResult result = store.recordAttempt(
                  initial.paymentIntentId(),
                  initial.clientSecret(),
                  true,
                  null,
                  MAX_ALLOWED_ATTEMPTS
            );

            assertThat(result.applied()).isTrue();
            assertThat(result.intent()).isNotNull();
            assertThat(result.intent().status()).isEqualTo(SUCCEEDED);
            assertThat(result.intent().attemptCount()).isEqualTo(1);
            assertThat(result.intent().lastErrorCode()).isNull();
        }

        @Test
        @DisplayName("records transient failure and keeps status REQUIRES_PAYMENT_METHOD with incremented attempts")
        void recordAttempt_transientFailure_incrementsAttemptAndKeepsRequiresPaymentMethod() {
            MockPaymentIntent initial = store.getOrCreate(IDEMPOTENCY_KEY, AMOUNT_CENTS);

            TransitionResult result = store.recordAttempt(
                  initial.paymentIntentId(),
                  initial.clientSecret(),
                  false,
                  "card_declined",
                  MAX_ALLOWED_ATTEMPTS
            );

            assertThat(result.applied()).isTrue();
            assertThat(result.intent().status()).isEqualTo(REQUIRES_PAYMENT_METHOD);
            assertThat(result.intent().attemptCount()).isEqualTo(1);
            assertThat(result.intent().lastErrorCode()).isEqualTo("card_declined");
        }

        @Test
        @DisplayName("records transient failure with null error code using default code")
        void recordAttempt_transientFailureNullErrorCode_usesDefaultErrorCode() {
            MockPaymentIntent initial = store.getOrCreate(IDEMPOTENCY_KEY, AMOUNT_CENTS);

            TransitionResult result = store.recordAttempt(
                  initial.paymentIntentId(),
                  initial.clientSecret(),
                  false,
                  null,
                  MAX_ALLOWED_ATTEMPTS
            );

            assertThat(result.applied()).isTrue();
            assertThat(result.intent().status()).isEqualTo(REQUIRES_PAYMENT_METHOD);
            assertThat(result.intent().lastErrorCode()).isEqualTo("card_declined");
        }

        @Test
        @DisplayName("transitions to CANCELED when attempt count reaches maxAllowedAttempts")
        void recordAttempt_maxAttemptsReached_transitionsToCanceled() {
            MockPaymentIntent initial = store.getOrCreate(IDEMPOTENCY_KEY, AMOUNT_CENTS);

            store.recordAttempt(initial.paymentIntentId(), initial.clientSecret(), false, "card_declined", MAX_ALLOWED_ATTEMPTS);
            store.recordAttempt(initial.paymentIntentId(), initial.clientSecret(), false, "card_declined", MAX_ALLOWED_ATTEMPTS);

            TransitionResult finalResult = store.recordAttempt(
                  initial.paymentIntentId(),
                  initial.clientSecret(),
                  false,
                  "card_declined",
                  MAX_ALLOWED_ATTEMPTS
            );

            assertThat(finalResult.applied()).isTrue();
            assertThat(finalResult.intent().status()).isEqualTo(CANCELED);
            assertThat(finalResult.intent().attemptCount()).isEqualTo(3);
            assertThat(finalResult.intent().lastErrorCode()).isEqualTo("card_declined");
        }

        @Test
        @DisplayName("transitions to CANCELED immediately on fraudulent error code regardless of attempt count")
        void recordAttempt_fraudulentCode_transitionsToCanceledImmediately() {
            MockPaymentIntent initial = store.getOrCreate(IDEMPOTENCY_KEY, AMOUNT_CENTS);

            TransitionResult result = store.recordAttempt(
                  initial.paymentIntentId(),
                  initial.clientSecret(),
                  false,
                  "fraudulent",
                  MAX_ALLOWED_ATTEMPTS
            );

            assertThat(result.applied()).isTrue();
            assertThat(result.intent().status()).isEqualTo(CANCELED);
            assertThat(result.intent().attemptCount()).isEqualTo(1);
            assertThat(result.intent().lastErrorCode()).isEqualTo("fraudulent");
        }

        @Test
        @DisplayName("returns applied=false when intent is already in terminal SUCCEEDED state")
        void recordAttempt_alreadySucceeded_doesNotApplyTransition() {
            MockPaymentIntent initial = store.getOrCreate(IDEMPOTENCY_KEY, AMOUNT_CENTS);

            store.recordAttempt(initial.paymentIntentId(), initial.clientSecret(), true, null, MAX_ALLOWED_ATTEMPTS);

            TransitionResult subsequentAttempt = store.recordAttempt(
                  initial.paymentIntentId(),
                  initial.clientSecret(),
                  true,
                  null,
                  MAX_ALLOWED_ATTEMPTS
            );

            assertThat(subsequentAttempt.applied()).isFalse();
            assertThat(subsequentAttempt.intent().status()).isEqualTo(SUCCEEDED);
            assertThat(subsequentAttempt.intent().attemptCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("returns applied=false when intent is already in terminal CANCELED state")
        void recordAttempt_alreadyCanceled_doesNotApplyTransition() {
            MockPaymentIntent initial = store.getOrCreate(IDEMPOTENCY_KEY, AMOUNT_CENTS);

            store.recordAttempt(initial.paymentIntentId(), initial.clientSecret(), false, "fraudulent", MAX_ALLOWED_ATTEMPTS);

            TransitionResult subsequentAttempt = store.recordAttempt(
                  initial.paymentIntentId(),
                  initial.clientSecret(),
                  true,
                  null,
                  MAX_ALLOWED_ATTEMPTS
            );

            assertThat(subsequentAttempt.applied()).isFalse();
            assertThat(subsequentAttempt.intent().status()).isEqualTo(CANCELED);
            assertThat(subsequentAttempt.intent().attemptCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("throws IllegalArgumentException when provided clientSecret does not match")
        void recordAttempt_invalidClientSecret_throwsIllegalArgumentException() {
            MockPaymentIntent initial = store.getOrCreate(IDEMPOTENCY_KEY, AMOUNT_CENTS);

            String paymentIntentId = initial.paymentIntentId();

            assertThatThrownBy(() -> store.recordAttempt(
                  paymentIntentId,
                  "invalid_secret",
                  true,
                  null,
                  MAX_ALLOWED_ATTEMPTS
            ))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessage("Invalid client_secret provided for payment_intent: " + paymentIntentId);
        }

        @Test
        @DisplayName("returns TransitionResult with null intent and applied=false when paymentIntentId does not exist")
        void recordAttempt_notFound_returnsResultWithNullIntent() {
            TransitionResult result = store.recordAttempt("non_existent_id", "some_secret", true, null, MAX_ALLOWED_ATTEMPTS);

            assertThat(result.applied()).isFalse();
            assertThat(result.intent()).isNull();
        }
    }

    @Nested
    @DisplayName("remove()")
    class Remove {

        @Test
        @DisplayName("removes intent from both paymentIntentId and idempotencyKey mappings")
        void remove_cleansUpBothMappings() {
            MockPaymentIntent intent = store.getOrCreate(IDEMPOTENCY_KEY, AMOUNT_CENTS);

            store.remove(intent.paymentIntentId());

            MockPaymentIntent brandNewIntent = store.getOrCreate(IDEMPOTENCY_KEY, AMOUNT_CENTS);
            assertThat(brandNewIntent.paymentIntentId()).isNotEqualTo(intent.paymentIntentId());
        }

        @Test
        @DisplayName("throws NullPointerException when paymentIntentId is null")
        void remove_nullPaymentIntentId_throwsNullPointerException() {
            assertThatThrownBy(() -> store.remove(null))
                  .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("refund()")
    class Refund {

        @Test
        @DisplayName("successfully transitions status from SUCCEEDED to REFUNDED")
        void refund_succeededIntent_transitionsToRefunded() {
            MockPaymentIntent initial = store.getOrCreate(IDEMPOTENCY_KEY, AMOUNT_CENTS);
            store.recordAttempt(initial.paymentIntentId(), initial.clientSecret(), true, null, MAX_ALLOWED_ATTEMPTS);

            MockPaymentIntent refunded = store.refund(initial.paymentIntentId());

            assertThat(refunded).isNotNull();
            assertThat(refunded.status()).isEqualTo(MockPaymentIntentStatus.REFUNDED);
        }

        @Test
        @DisplayName("replays idempotently without exception when intent is already REFUNDED")
        void refund_alreadyRefunded_returnsSameIntentWithoutError() {
            MockPaymentIntent initial = store.getOrCreate(IDEMPOTENCY_KEY, AMOUNT_CENTS);
            store.recordAttempt(initial.paymentIntentId(), initial.clientSecret(), true, null, MAX_ALLOWED_ATTEMPTS);

            MockPaymentIntent firstRefund = store.refund(initial.paymentIntentId());
            MockPaymentIntent secondRefund = store.refund(initial.paymentIntentId());

            assertThat(secondRefund).isNotNull();
            assertThat(secondRefund.status()).isEqualTo(MockPaymentIntentStatus.REFUNDED);
            assertThat(secondRefund).isEqualTo(firstRefund);
        }

        @Test
        @DisplayName("throws IllegalStateException when intent is in REQUIRES_PAYMENT_METHOD status")
        void refund_requiresPaymentMethodIntent_throwsIllegalStateException() {
            MockPaymentIntent initial = store.getOrCreate(IDEMPOTENCY_KEY, AMOUNT_CENTS);

            String paymentIntentId = initial.paymentIntentId();

            assertThatThrownBy(() -> store.refund(paymentIntentId))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessage("Cannot refund intent in status REQUIRES_PAYMENT_METHOD");
        }

        @Test
        @DisplayName("throws IllegalStateException when intent is in CANCELED status")
        void refund_canceledIntent_throwsIllegalStateException() {
            MockPaymentIntent initial = store.getOrCreate(IDEMPOTENCY_KEY, AMOUNT_CENTS);
            store.recordAttempt(initial.paymentIntentId(), initial.clientSecret(), false, "fraudulent", MAX_ALLOWED_ATTEMPTS);

            String paymentIntentId = initial.paymentIntentId();

            assertThatThrownBy(() -> store.refund(paymentIntentId))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessage("Cannot refund intent in status CANCELED");
        }

        @Test
        @DisplayName("returns null when paymentIntentId does not exist in store")
        void refund_nonExistentIntent_returnsNull() {
            MockPaymentIntent result = store.refund("pi_mock_non_existent");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("throws NullPointerException when paymentIntentId is null")
        void refund_nullPaymentIntentId_throwsNullPointerException() {
            assertThatThrownBy(() -> store.refund(null))
                  .isInstanceOf(NullPointerException.class);
        }
    }
}