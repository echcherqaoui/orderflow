package com.echcherqaoui.orderflow.order.sse;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of active SseEmitters keyed by orderId.
 * Single-instance only — does not support horizontal scaling.
 * If order-service ever runs >1 replica, completions may be dropped
 * for emitters registered on a different instance. Revisit with
 * Redis pub/sub if that changes.
 */
@Component
public class SseEmitterRegistry {

    private static final long TIMEOUT_MILLIS = 5 * 60 * 1000L; // 5 min

    private final Map<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter register(UUID orderId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        emitters.put(orderId, emitter);

        emitter.onCompletion(() -> emitters.remove(orderId, emitter));
        emitter.onTimeout(() -> {
            emitters.remove(orderId, emitter);
            emitter.complete();
        });
        emitter.onError(e -> emitters.remove(orderId, emitter));

        return emitter;
    }

    public void remove(UUID orderId) {
        emitters.remove(orderId);
    }
}