package com.echcherqaoui.orderflow.payment.service;

import com.echcherqaoui.orderflow.payment.dto.StripeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class StripeWebhookSignatureVerifierTest {

    private static final String SECRET = "whsec_test_secret_key_12345";
    private static final String PAYLOAD = "{\"id\":\"evt_123\",\"type\":\"payment_intent.succeeded\"}";

    @Mock
    private StripeProperties stripeProperties;

    private StripeWebhookSignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new StripeWebhookSignatureVerifier(stripeProperties);
    }

    private String computeHmac(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String buildHeader(long timestamp, String signature) {
        return "t=" + timestamp + ",v1=" + signature;
    }

    @Nested
    @DisplayName("Valid Signatures")
    class ValidSignatures {

        @Test
        @DisplayName("returns true for a valid signature within time tolerance window")
        void verify_validSignature_returnsTrue() {
            given(stripeProperties.webhookSecret()).willReturn(SECRET);

            long now = Instant.now().getEpochSecond();
            String signedPayload = now + "." + PAYLOAD;
            String signature = computeHmac(SECRET, signedPayload);
            String header = buildHeader(now, signature);

            boolean result = verifier.verify(PAYLOAD, header);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns true when header contains additional scheme parameters or whitespace")
        void verify_extraHeaderParameters_returnsTrue() {
            given(stripeProperties.webhookSecret()).willReturn(SECRET);

            long now = Instant.now().getEpochSecond();
            String signature = computeHmac(SECRET, now + "." + PAYLOAD);
            String header = "t=" + now + ", v0=legacy_sig, v1=" + signature + " , unknown=param";

            boolean result = verifier.verify(PAYLOAD, header);

            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("Invalid Signatures & Tampering")
    class InvalidSignatures {

        @Test
        @DisplayName("returns false when computed HMAC signature does not match header signature")
        void verify_mismatchedSignature_returnsFalse() {
            given(stripeProperties.webhookSecret()).willReturn(SECRET);

            long now = Instant.now().getEpochSecond();
            String header = buildHeader(now, "invalid_signature_hash");

            boolean result = verifier.verify(PAYLOAD, header);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false when payload is tampered after signing")
        void verify_tamperedPayload_returnsFalse() {
            given(stripeProperties.webhookSecret()).willReturn(SECRET);

            long now = Instant.now().getEpochSecond();
            String signature = computeHmac(SECRET, now + "." + PAYLOAD);
            String header = buildHeader(now, signature);

            boolean result = verifier.verify(PAYLOAD + "_tampered", header);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false when signature was generated with a different secret")
        void verify_differentSecret_returnsFalse() {
            given(stripeProperties.webhookSecret()).willReturn(SECRET);

            long now = Instant.now().getEpochSecond();
            String signature = computeHmac("whsec_wrong_secret", now + "." + PAYLOAD);
            String header = buildHeader(now, signature);

            boolean result = verifier.verify(PAYLOAD, header);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("Timestamp Tolerance & Replay Protection")
    class TimestampTolerance {

        @Test
        @DisplayName("returns false when timestamp is older than 300 seconds tolerance")
        void verify_expiredTimestamp_returnsFalse() {
            given(stripeProperties.webhookSecret()).willReturn(SECRET);

            long oldTimestamp = Instant.now().getEpochSecond() - 301;
            String signature = computeHmac(SECRET, oldTimestamp + "." + PAYLOAD);
            String header = buildHeader(oldTimestamp, signature);

            boolean result = verifier.verify(PAYLOAD, header);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false when timestamp is in the future beyond 300 seconds tolerance")
        void verify_futureTimestampExceedingTolerance_returnsFalse() {
            given(stripeProperties.webhookSecret()).willReturn(SECRET);

            long futureTimestamp = Instant.now().getEpochSecond() + 301;
            String signature = computeHmac(SECRET, futureTimestamp + "." + PAYLOAD);
            String header = buildHeader(futureTimestamp, signature);

            boolean result = verifier.verify(PAYLOAD, header);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("Malformed Inputs & Edge Cases")
    class MalformedInputs {

        @ParameterizedTest(name = "[{index}] returns false when webhook secret is {0}")
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void verify_invalidSecret_returnsFalse(String secret) {
            given(stripeProperties.webhookSecret()).willReturn(secret);

            long now = Instant.now().getEpochSecond();
            String header = buildHeader(now, "sig");

            boolean result = verifier.verify(PAYLOAD, header);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false when payload is null")
        void verify_nullPayload_returnsFalse() {
            given(stripeProperties.webhookSecret()).willReturn(SECRET);

            boolean result = verifier.verify(null, "t=123456,v1=sig");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false when signature header is null")
        void verify_nullHeader_returnsFalse() {
            given(stripeProperties.webhookSecret()).willReturn(SECRET);

            boolean result = verifier.verify(PAYLOAD, null);

            assertThat(result).isFalse();
        }

        @ParameterizedTest(name = "[{index}] returns false for malformed header: \"{0}\"")
        @ValueSource(strings = {
              "invalid_header_format",
              "t=not_a_number,v1=sig",
              "t=123456",
              "v1=sig_only",
              "t=,v1=sig",
              "t=123456,v1="
        })
        void verify_malformedHeader_returnsFalse(String malformedHeader) {
            given(stripeProperties.webhookSecret()).willReturn(SECRET);

            boolean result = verifier.verify(PAYLOAD, malformedHeader);

            assertThat(result).isFalse();
        }
    }
}