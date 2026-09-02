package com.echcherqaoui.orderflow.security.service.impl;

import com.echcherqaoui.orderflow.security.service.SignatureService;
import org.jspecify.annotations.NonNull;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import static java.nio.charset.StandardCharsets.UTF_8;

public class HmacSignatureService implements SignatureService {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec secretKeySpec;

    public HmacSignatureService(@NonNull String secret) {
        if (secret.isBlank())
            throw new IllegalArgumentException("HMAC secret must not be blank");

        this.secretKeySpec = new SecretKeySpec(secret.getBytes(UTF_8), ALGORITHM);
    }

    private String computeHmac(@NonNull String data) {

        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(secretKeySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes(UTF_8));

            return Base64.getUrlEncoder()
                  .withoutPadding()
                  .encodeToString(hmacBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to compute HMAC signature", e);
        }
    }

    // MessageDigest.isEqual is constant-time and null-safe
    private boolean constantTimeEquals(String expected, String provided) {
        if (provided == null || expected == null) return false;

        return MessageDigest.isEqual(
              expected.getBytes(UTF_8),
              provided.getBytes(UTF_8)
        );
    }

    @NonNull
    private String canonicalize( String @NonNull... payloadParts) {
        StringBuilder sb = new StringBuilder();
        for (String p : payloadParts) {
            // p is guaranteed non-null and is already a String
            sb.append(p.length()).append(':').append(p);
        }
        return sb.toString();
    }

    private void validatePayloadParts(String[] payloadParts) {
        if (payloadParts == null || payloadParts.length == 0)
            throw new IllegalArgumentException("payloadParts array must not be null or empty");

        for (int i = 0; i < payloadParts.length; i++)
            if (payloadParts[i] == null)
                throw new IllegalArgumentException("payloadParts element at index " + i + " must not be null");
    }

    @Override
    public String sign(String... payloadParts) {
        validatePayloadParts(payloadParts);

        return computeHmac(canonicalize(payloadParts));
    }

    @Override
    public boolean verify(String signature, String... payloadParts) {
        if (signature == null || signature.isBlank())
            return false;

        String expected = sign(payloadParts);

        return constantTimeEquals(expected, signature);
    }
}