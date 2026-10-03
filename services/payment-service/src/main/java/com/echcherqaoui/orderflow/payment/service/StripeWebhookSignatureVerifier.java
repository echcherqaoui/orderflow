package com.echcherqaoui.orderflow.payment.service;

import com.echcherqaoui.orderflow.payment.dto.StripeProperties;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
@Component
@RequiredArgsConstructor
public class StripeWebhookSignatureVerifier {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final long DEFAULT_TOLERANCE_SECONDS = 300;

    private final StripeProperties stripeProperties;

    private static String hmacHex(@NonNull String secret, byte[]... byteArrays) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(UTF_8), HMAC_SHA256));
            for (byte[] bytes : byteArrays)
                mac.update(bytes);

            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256 signature", e);
        }
    }

    public boolean verify(String rawPayload, String sigHeader) {
        String webhookSecret = stripeProperties.webhookSecret();
        if (rawPayload == null || sigHeader == null || webhookSecret == null || webhookSecret.isBlank())
            return false;

        long timestamp = -1;
        String expectedSignature = null;

        for (String item : sigHeader.split(",")) {
            String[] parts = item.trim().split("=", 2);
            if (parts.length != 2)
                continue;

            if ("t".equals(parts[0]))
                try {
                    timestamp = Long.parseLong(parts[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            else if ("v1".equals(parts[0]))
                expectedSignature = parts[1];
        }

        if (timestamp == -1 || expectedSignature == null)
            return false;

        // Reject events outside tolerance threshold (5 mins)
        long currentTimestamp = Instant.now().getEpochSecond();
        if (Math.abs(currentTimestamp - timestamp) > DEFAULT_TOLERANCE_SECONDS) {
            log.warn(
                  "Stripe webhook timestamp is outside tolerance threshold. Timestamp: {}, Current: {}",
                  timestamp,
                  currentTimestamp
            );
            return false;
        }

        byte[] prefixBytes = (timestamp + ".").getBytes(UTF_8);
        byte[] payloadBytes = rawPayload.getBytes(UTF_8);

        String computedSignature = hmacHex(webhookSecret, prefixBytes, payloadBytes);

        // Constant-time comparison to prevent timing attacks
        return MessageDigest.isEqual(
              computedSignature.getBytes(UTF_8),
              expectedSignature.getBytes(UTF_8)
        );
    }
}