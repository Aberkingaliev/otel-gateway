package com.acme.finops.gateway.wire.mutate;

import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.wire.cursor.EvalScratch;
import com.acme.finops.gateway.wire.cursor.FastWireCursor;

/**
 * Selects value spans from wire-level payload fields that match
 * a compiled path expression, for subsequent masking or redaction.
 *
 * <p>Not thread-safe; the supplied cursor and scratch are per-thread resources.
 */
interface ValueSpanSelector {
    /**
     * Scans the packet and collects matching value spans into the collector.
     *
     * @param packetRef the source packet
     * @param cursor    reusable wire cursor (reset by the caller)
     * @param scratch   per-thread evaluation scratch space
     * @param collector receives the byte ranges of matched value spans
     * @return the number of spans collected
     */
    int collect(PacketRef packetRef,
                FastWireCursor cursor,
                EvalScratch scratch,
                ValueSpanCollector collector);
}
