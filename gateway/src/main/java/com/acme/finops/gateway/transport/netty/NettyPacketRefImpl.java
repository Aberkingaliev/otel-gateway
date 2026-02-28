package com.acme.finops.gateway.transport.netty;

import com.acme.finops.gateway.memory.PacketDescriptor;
import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.transport.api.ProtocolKind;
import com.acme.finops.gateway.transport.api.SignalKind;
import io.netty.buffer.ByteBuf;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class NettyPacketRefImpl implements PacketRef {
    private static final AtomicLong PACKET_IDS = new AtomicLong(1);

    private final ByteBuf byteBuf;
    private final PacketDescriptor descriptor;
    private final MemorySegment segment;
    private final int offset;
    private final int length;
    private final AtomicInteger refCount = new AtomicInteger(1);

    public NettyPacketRefImpl(ByteBuf byteBuf) {
        this(byteBuf, null, null);
    }

    public NettyPacketRefImpl(ByteBuf byteBuf, SignalKind signalKind, ProtocolKind protocolKind) {
        Objects.requireNonNull(byteBuf, "byteBuf");
        if (!byteBuf.isDirect() || !byteBuf.hasMemoryAddress()) {
            throw new IllegalArgumentException("NettyPacketRefImpl requires direct ByteBuf with memory address");
        }

        // Netty keeps ownership and lifetime via reference counting.
        // We retain once here and release when our refCount reaches zero.
        // Arena.global() is safe because this segment does not own memory;
        // validity is bounded by ByteBuf ref-count contract.
        this.byteBuf = byteBuf.retain();
        this.offset = 0;
        this.length = byteBuf.readableBytes();

        long nativeAddress = byteBuf.memoryAddress() + byteBuf.readerIndex();
        this.segment = MemorySegment.ofAddress(nativeAddress)
            .reinterpret(this.length, Arena.global(), __ -> { });

        long packetId = PACKET_IDS.getAndIncrement();
        this.descriptor = new PacketDescriptor(
            packetId,
            0L,
            signalKind,
            protocolKind,
            0,
            this.length,
            System.nanoTime()
        );
    }

    @Override
    public long packetId() {
        return descriptor.packetId();
    }

    @Override
    public PacketDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public MemorySegment segment() {
        return segment;
    }

    @Override
    public int offset() {
        return offset;
    }

    @Override
    public int length() {
        return length;
    }

    @Override
    public int refCount() {
        return refCount.get();
    }

    @Override
    public boolean isExclusiveOwner() {
        return refCount.get() == 1;
    }

    @Override
    public PacketRef retain() {
        while (true) {
            int current = refCount.get();
            if (current <= 0) {
                throw new IllegalStateException("Retain after release, packetId=" + packetId());
            }
            if (refCount.compareAndSet(current, current + 1)) {
                return this;
            }
        }
    }

    @Override
    public boolean release() {
        while (true) {
            int current = refCount.get();
            if (current <= 0) {
                throw new IllegalStateException("Double release, packetId=" + packetId());
            }
            int next = current - 1;
            if (!refCount.compareAndSet(current, next)) {
                continue;
            }
            if (next == 0) {
                byteBuf.release();
                return true;
            }
            return false;
        }
    }
}
