package com.acme.finops.gateway.wire.mutate;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.zip.CRC32;

public final class Crc32TailIntegrityRepair implements IntegrityRepair {
    private final Endianness endianness;

    public Crc32TailIntegrityRepair(Endianness endianness) {
        this.endianness = endianness == null ? Endianness.LITTLE : endianness;
    }

    @Override
    public void repair(MemorySegment payload, int payloadLength) {
        if (payloadLength < 4) {
            throw new IllegalArgumentException("CRC32 trailer requires at least 4 bytes");
        }
        int checksumOffset = payloadLength - 4;

        CRC32 crc32 = new CRC32();
        for (int i = 0; i < checksumOffset; i++) {
            crc32.update(payload.get(ValueLayout.JAVA_BYTE, i) & 0xFF);
        }

        long checksum = crc32.getValue();
        if (endianness == Endianness.LITTLE) {
            payload.set(ValueLayout.JAVA_BYTE, checksumOffset, (byte) (checksum & 0xFF));
            payload.set(ValueLayout.JAVA_BYTE, checksumOffset + 1, (byte) ((checksum >>> 8) & 0xFF));
            payload.set(ValueLayout.JAVA_BYTE, checksumOffset + 2, (byte) ((checksum >>> 16) & 0xFF));
            payload.set(ValueLayout.JAVA_BYTE, checksumOffset + 3, (byte) ((checksum >>> 24) & 0xFF));
            return;
        }

        payload.set(ValueLayout.JAVA_BYTE, checksumOffset, (byte) ((checksum >>> 24) & 0xFF));
        payload.set(ValueLayout.JAVA_BYTE, checksumOffset + 1, (byte) ((checksum >>> 16) & 0xFF));
        payload.set(ValueLayout.JAVA_BYTE, checksumOffset + 2, (byte) ((checksum >>> 8) & 0xFF));
        payload.set(ValueLayout.JAVA_BYTE, checksumOffset + 3, (byte) (checksum & 0xFF));
    }

    public enum Endianness {
        LITTLE,
        BIG
    }
}
