package com.acme.finops.gateway.transport.netty;

import com.acme.finops.gateway.transport.api.ProtocolKind;
import com.acme.finops.gateway.transport.api.SignalKind;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyPacketRefImplTest {

    @Test
    void shouldExposeDirectByteBufAsMemorySegmentAndHonorRefCounting() {
        ByteBuf buf = Unpooled.directBuffer(8);
        try {
            buf.writeBytes(new byte[]{1, 2, 3, 4});
            int initial = buf.refCnt();

            NettyPacketRefImpl ref = new NettyPacketRefImpl(buf, SignalKind.TRACES, ProtocolKind.OTLP_HTTP_PROTO);
            assertEquals(initial + 1, buf.refCnt(), "Constructor retains ByteBuf once");

            assertEquals(4, ref.length());
            assertEquals(1, ref.refCount());
            assertTrue(ref.isExclusiveOwner());

            byte[] copied = ref.segment()
                .asSlice(ref.offset(), ref.length())
                .toArray(ValueLayout.JAVA_BYTE);
            assertArrayEquals(new byte[]{1, 2, 3, 4}, copied);

            ref.retain();
            assertEquals(2, ref.refCount());
            assertFalse(ref.isExclusiveOwner());

            assertFalse(ref.release(), "First release should not free ByteBuf");
            assertEquals(1, ref.refCount());
            assertEquals(initial + 1, buf.refCnt());

            assertTrue(ref.release(), "Final release should free ByteBuf retain");
            assertEquals(initial, buf.refCnt(), "ByteBuf refCnt should return to initial");
        } finally {
            buf.release();
        }
    }

    @Test
    void shouldRejectHeapByteBuf() {
        ByteBuf heap = Unpooled.buffer(4);
        try {
            heap.writeInt(123);
            assertThrows(IllegalArgumentException.class, () ->
                new NettyPacketRefImpl(heap, SignalKind.TRACES, ProtocolKind.OTLP_HTTP_PROTO));
        } finally {
            heap.release();
        }
    }
}

