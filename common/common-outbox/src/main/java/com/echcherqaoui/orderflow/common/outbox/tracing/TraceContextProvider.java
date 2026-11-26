package com.echcherqaoui.orderflow.common.outbox.tracing;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@RequiredArgsConstructor
public class TraceContextProvider {

    private final Tracer tracer;
    private final Propagator propagator;

    public String currentTraceparent() {
        var context = tracer.currentTraceContext().context();
        if (context == null) return null;
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(context, carrier, Map::put);
        return carrier.get("traceparent");
    }
}