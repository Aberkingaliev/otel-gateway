package com.acme.finops.gateway.wire.mutate;

import com.acme.finops.gateway.memory.PacketDescriptor;
import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.memory.PacketRefImpl;
import com.acme.finops.gateway.memory.SlabPacketAllocator;
import com.acme.finops.gateway.transport.api.ProtocolKind;
import com.acme.finops.gateway.transport.api.SignalKind;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class DefaultReframeWriterTest {

    @Test
    void shouldApplyLenVarintGrowthPatchAndShiftTail() {
        byte[] sourceBytes = lenMessage(127);
        PacketRef source = packet(sourceBytes);
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(1024 * 1024)) {
            MutationPlan plan = new MutationPlan.Builder(1L)
                .mode(MutationPlan.PlanMode.REFRAME)
                .sourceLength(sourceBytes.length)
                .targetLength(sourceBytes.length + 1) // body grows by 1 byte
                .addPassB(new MutationPlan.SliceCopyOp(0, sourceBytes.length, 0))
                .addPassB(new MutationPlan.OverwriteOp(sourceBytes.length, new byte[]{0x7F}, "append"))
                .addLengthDelta(new MutationPlan.LengthDelta(0, -1, 0, 1, 127, 128))
                .build();

            try {
                ReframeResult.Success success = assertInstanceOf(
                    ReframeResult.Success.class,
                    new DefaultReframeWriter().write(plan, source, allocator)
                );
                PacketRef reframed = success.reframed();
                try {
                    byte[] out = toBytes(reframed);
                    assertEquals(131, out.length);
                    assertEquals((byte) 0x0A, out[0]);
                    assertEquals((byte) 0x80, out[1]);
                    assertEquals((byte) 0x01, out[2]);
                    for (int i = 0; i < 127; i++) {
                        assertEquals((byte) i, out[3 + i]);
                    }
                    assertEquals((byte) 0x7F, out[130]);
                } finally {
                    reframed.release();
                }
            } finally {
                source.release();
            }
        }
    }

    @Test
    void shouldApplyLenVarintShrinkPatchAndShiftTail() {
        byte[] sourceBytes = lenMessage(128);
        PacketRef source = packet(sourceBytes);
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(1024 * 1024)) {
            MutationPlan plan = new MutationPlan.Builder(2L)
                .mode(MutationPlan.PlanMode.REFRAME)
                .sourceLength(sourceBytes.length)
                .targetLength(sourceBytes.length - 1) // body shrinks by 1 byte
                .addPassB(new MutationPlan.SliceCopyOp(0, sourceBytes.length - 1, 0))
                .addLengthDelta(new MutationPlan.LengthDelta(0, -1, 0, 1, 128, 127))
                .build();

            try {
                ReframeResult.Success success = assertInstanceOf(
                    ReframeResult.Success.class,
                    new DefaultReframeWriter().write(plan, source, allocator)
                );
                PacketRef reframed = success.reframed();
                try {
                    byte[] out = toBytes(reframed);
                    assertEquals(129, out.length);
                    assertEquals((byte) 0x0A, out[0]);
                    assertEquals((byte) 0x7F, out[1]);
                    for (int i = 0; i < 127; i++) {
                        assertEquals((byte) i, out[2 + i]);
                    }
                } finally {
                    reframed.release();
                }
            } finally {
                source.release();
            }
        }
    }

    @Test
    void shouldRepairCrc32TrailerWhenConfigured() {
        byte[] sourceBytes = new byte[]{1, 2, 3, 4, 0, 0, 0, 0};
        PacketRef source = packet(sourceBytes);
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(1024 * 1024)) {
            MutationPlan plan = new MutationPlan.Builder(3L)
                .mode(MutationPlan.PlanMode.REFRAME)
                .sourceLength(sourceBytes.length)
                .targetLength(sourceBytes.length)
                .addPassB(new MutationPlan.SliceCopyOp(0, sourceBytes.length, 0))
                .build();

            DefaultReframeWriter writer = new DefaultReframeWriter(
                new LenCascadeRecalculator(),
                new Crc32TailIntegrityRepair(Crc32TailIntegrityRepair.Endianness.LITTLE)
            );

            try {
                ReframeResult.Success success = assertInstanceOf(ReframeResult.Success.class, writer.write(plan, source, allocator));
                PacketRef reframed = success.reframed();
                try {
                    byte[] out = toBytes(reframed);
                    CRC32 crc32 = new CRC32();
                    crc32.update(out, 0, 4);
                    long expected = crc32.getValue();
                    byte[] expectedTrailer = new byte[]{
                        (byte) (expected & 0xFF),
                        (byte) ((expected >>> 8) & 0xFF),
                        (byte) ((expected >>> 16) & 0xFF),
                        (byte) ((expected >>> 24) & 0xFF)
                    };
                    assertArrayEquals(expectedTrailer, new byte[]{out[4], out[5], out[6], out[7]});
                } finally {
                    reframed.release();
                }
            } finally {
                source.release();
            }
        }
    }

    private static byte[] lenMessage(int bodyLength) {
        int varintSize = LenCascadeRecalculator.varintSize(bodyLength);
        byte[] out = new byte[1 + varintSize + bodyLength];
        out[0] = 0x0A;
        int p = 1;
        int v = bodyLength;
        while ((v & ~0x7F) != 0) {
            out[p++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out[p++] = (byte) v;
        for (int i = 0; i < bodyLength; i++) {
            out[p + i] = (byte) i;
        }
        return out;
    }

    private static PacketRef packet(byte[] payload) {
        PacketDescriptor descriptor = new PacketDescriptor(
            1L,
            1L,
            SignalKind.TRACES,
            ProtocolKind.OTLP_HTTP_PROTO,
            0,
            payload.length,
            System.nanoTime()
        );
        return new PacketRefImpl(1L, descriptor, MemorySegment.ofArray(payload), 0, payload.length);
    }

    private static byte[] toBytes(PacketRef packetRef) {
        return packetRef.segment()
            .asSlice(packetRef.offset(), packetRef.length())
            .toArray(ValueLayout.JAVA_BYTE);
    }
}
