package com.acme.finops.gateway.wire.mutate;

final class ValueSpanCollector {
    private final int[] offsets;
    private final int[] lengths;
    private int count;

    ValueSpanCollector(int capacity) {
        this.offsets = new int[Math.max(1, capacity)];
        this.lengths = new int[Math.max(1, capacity)];
    }

    void reset() {
        count = 0;
    }

    boolean add(int offset, int length) {
        if (count >= offsets.length) {
            return false;
        }
        offsets[count] = offset;
        lengths[count] = length;
        count++;
        return true;
    }

    int count() {
        return count;
    }

    int capacity() {
        return offsets.length;
    }

    int offsetAt(int idx) {
        return offsets[idx];
    }

    int lengthAt(int idx) {
        return lengths[idx];
    }
}
