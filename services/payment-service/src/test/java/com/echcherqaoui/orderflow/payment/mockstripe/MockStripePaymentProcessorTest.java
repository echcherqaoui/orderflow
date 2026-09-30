package com.echcherqaoui.orderflow.payment.mockstripe;

import com.echcherqaoui.orderflow.payment.mockstripe.dto.MockPspProperties;
import com.echcherqaoui.orderflow.payment.mockstripe.dto.MockStripeWebhookPayload.PaymentError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class MockStripePaymentProcessorTest {

    @Mock
    private MockPspProperties mockPspProperties;

    @InjectMocks
    private MockStripePaymentProcessor paymentProcessor;

    @Nested
    @DisplayName("decideOutcome()")
    class DecideOutcome {

        @ParameterizedTest
        @ValueSource(strings = {
              "pm_card_chargeDeclined",
              "pm_card_insufficientFunds",
              "pm_card_radarBlock",
              "pm_card_fraudulent"
        })
        @DisplayName("returns false deterministically for explicit decline payment tokens")
        void decideOutcome_explicitDeclineTokens_returnsFalse(String paymentMethod) {
            boolean outcome = paymentProcessor.decideOutcome(paymentMethod);

            assertThat(outcome).isFalse();
        }

        @Test
        @DisplayName("returns true for standard card when failure rate is 0.0")
        void decideOutcome_zeroFailureRate_returnsTrue() {
            given(mockPspProperties.getFailureRate()).willReturn(0.0);

            boolean outcome = paymentProcessor.decideOutcome("pm_card_visa");

            assertThat(outcome).isTrue();
        }

        @Test
        @DisplayName("returns false for standard card when failure rate is 1.0")
        void decideOutcome_hundredPercentFailureRate_returnsFalse() {
            given(mockPspProperties.getFailureRate()).willReturn(1.0);

            boolean outcome = paymentProcessor.decideOutcome("pm_card_visa");

            assertThat(outcome).isFalse();
        }
    }

    @Nested
    @DisplayName("resolvePaymentError()")
    class ResolvePaymentError {

        @ParameterizedTest
        @ValueSource(strings = {"pm_card_radarBlock", "pm_card_fraudulent"})
        @DisplayName("maps fraud and radar blocks to fraudulent error code")
        void resolvePaymentError_fraudTokens_returnsFraudError(String paymentMethod) {
            PaymentError error = paymentProcessor.resolvePaymentError(paymentMethod);

            assertThat(error).isNotNull();
            assertThat(error.code()).isEqualTo("fraudulent");
            assertThat(error.message()).isEqualTo("Transaction blocked due to high fraud risk.");
        }

        @Test
        @DisplayName("maps insufficient funds token to insufficient_funds error code")
        void resolvePaymentError_insufficientFundsToken_returnsInsufficientFundsError() {
            PaymentError error = paymentProcessor.resolvePaymentError("pm_card_insufficientFunds");

            assertThat(error).isNotNull();
            assertThat(error.code()).isEqualTo("insufficient_funds");
            assertThat(error.message()).isEqualTo("Your card has insufficient funds.");
        }

        @ParameterizedTest
        @ValueSource(strings = {
              "pm_card_chargeDeclined",
              "pm_card_unknown",
              "pm_card_visa"
        })
        @DisplayName("defaults to card_declined error code for other payment methods")
        void resolvePaymentError_defaultTokens_returnsCardDeclinedError(String paymentMethod) {
            PaymentError error = paymentProcessor.resolvePaymentError(paymentMethod);

            assertThat(error).isNotNull();
            assertThat(error.code()).isEqualTo("card_declined");
            assertThat(error.message()).isEqualTo("Your card was declined.");
        }
    }
}