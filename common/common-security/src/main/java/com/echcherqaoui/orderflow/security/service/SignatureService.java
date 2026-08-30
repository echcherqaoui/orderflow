package com.echcherqaoui.orderflow.security.service;

/**
 * Contract for event signature signing and verification across microservices.
 */
public interface SignatureService {


    /**
     * Signs an event by computing an HMAC-SHA256 signature over its critical payload fields.
     *
     * @param payloadParts the critical fields required for integrity verification (e.g., orderId, amount)
     * @return hex-encoded signature string
     */
    String sign(Object ... payloadParts);

    /**
     * Verifies that a received signature matches the computed HMAC-SHA256 over the payload parts.
     * Implementations must perform a constant-time comparison to prevent timing side-channel attacks.
     *
     * @param signature    the hex-encoded signature to verify
     * @param payloadParts the critical fields used to reconstruct the expected signature
     * @return {@code true} if the signature is valid, {@code false} otherwise
     */
    boolean verify(String signature, Object ... payloadParts);
}