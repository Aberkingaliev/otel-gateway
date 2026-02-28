package com.acme.finops.gateway.wire.mutate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes cascading protobuf LEN updates when nested body sizes change.
 *
 * Strategy (bottom-up):
 * 1) Build explicit frame tree from frameId/parentFrameId.
 * 2) Process children before parent (post-order).
 * 3) Bubble up each child encoded-size delta (body + LEN varint) to parent body.
 * 4) Calculate varint size transitions for each affected length field.
 */
public final class LenCascadeRecalculator {

    public RecalculationResult recalculate(MutationPlan plan) {
        Map<Integer, FrameState> byId = new HashMap<>();
        Map<Integer, List<Integer>> childrenByParent = new HashMap<>();

        for (MutationPlan.LengthDelta d : plan.lengthDeltas()) {
            FrameState prev = byId.putIfAbsent(
                d.frameId(),
                new FrameState(
                    d.frameId(),
                    d.parentFrameId(),
                    d.frameStartOffset(),
                    d.lengthFieldOffset(),
                    d.oldBodyLength(),
                    d.newBodyLength()
                )
            );
            if (prev != null) {
                throw new IllegalArgumentException("Duplicate frameId in lengthDeltas: " + d.frameId());
            }
            childrenByParent.computeIfAbsent(d.parentFrameId(), ignored -> new ArrayList<>()).add(d.frameId());
        }

        for (FrameState state : byId.values()) {
            int parentId = state.parentFrameId;
            if (parentId != -1 && !byId.containsKey(parentId)) {
                throw new IllegalArgumentException(
                    "Missing parent frameId=" + parentId + " for frameId=" + state.frameId
                );
            }
        }

        List<FrameState> postOrder = buildPostOrder(byId, childrenByParent);
        for (FrameState parent : postOrder) {
            int cascadedBodyLength = parent.localNewBodyLength;
            List<Integer> childIds = childrenByParent.getOrDefault(parent.frameId, List.of());
            for (int childId : childIds) {
                FrameState child = byId.get(childId);
                cascadedBodyLength += child.encodedSizeDelta();
            }
            if (cascadedBodyLength < 0) {
                throw new IllegalArgumentException("Cascaded body length became negative for frameId=" + parent.frameId);
            }
            parent.effectiveNewBodyLength = cascadedBodyLength;
        }

        List<VarintPatch> patches = new ArrayList<>();
        int totalVarintDelta = 0;
        for (FrameState frame : byId.values()) {
            int oldVarint = varintSize(frame.oldBodyLength);
            int newVarint = varintSize(frame.effectiveNewBodyLength);
            totalVarintDelta += (newVarint - oldVarint);
            patches.add(new VarintPatch(
                frame.frameId,
                frame.parentFrameId,
                frame.frameStartOffset,
                frame.lengthFieldOffset,
                frame.oldBodyLength,
                frame.effectiveNewBodyLength,
                oldVarint,
                newVarint
            ));
        }
        patches.sort((a, b) -> Integer.compare(a.lengthFieldOffset(), b.lengthFieldOffset()));

        int baseTargetLength = plan.targetLength() > 0 ? plan.targetLength() : plan.sourceLength();
        int target = baseTargetLength > 0 ? baseTargetLength + totalVarintDelta : 0;
        return new RecalculationResult(target, List.copyOf(patches));
    }

    private static List<FrameState> buildPostOrder(Map<Integer, FrameState> byId,
                                                   Map<Integer, List<Integer>> childrenByParent) {
        List<FrameState> postOrder = new ArrayList<>(byId.size());
        Set<Integer> visiting = new HashSet<>();
        Set<Integer> visited = new HashSet<>();

        for (int frameId : byId.keySet()) {
            dfs(frameId, byId, childrenByParent, visiting, visited, postOrder);
        }

        return postOrder;
    }

    private static void dfs(int frameId,
                            Map<Integer, FrameState> byId,
                            Map<Integer, List<Integer>> childrenByParent,
                            Set<Integer> visiting,
                            Set<Integer> visited,
                            List<FrameState> postOrder) {
        if (visited.contains(frameId)) {
            return;
        }
        if (!visiting.add(frameId)) {
            throw new IllegalArgumentException("Cycle detected in LengthDelta hierarchy at frameId=" + frameId);
        }
        for (int childId : childrenByParent.getOrDefault(frameId, List.of())) {
            if (visiting.contains(childId)) {
                throw new IllegalArgumentException("Cycle detected in LengthDelta hierarchy at frameId=" + childId);
            }
            if (!visited.contains(childId)) {
                dfs(childId, byId, childrenByParent, visiting, visited, postOrder);
            }
        }
        visiting.remove(frameId);
        visited.add(frameId);
        postOrder.add(byId.get(frameId));
    }

    /**
     * Key helper requested for cascade logic:
     * returns how many bytes varint length field grows/shrinks after body length change.
     */
    public int computeVarintSizeDelta(int oldBodyLength, int newBodyLength) {
        return varintSize(newBodyLength) - varintSize(oldBodyLength);
    }

    public static int varintSize(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be >= 0");
        }
        // Fast path using bit width: ceil(bitLength / 7), with bitLength(0)=1.
        int bitLength = Integer.SIZE - Integer.numberOfLeadingZeros(value | 1);
        return (bitLength + 6) / 7;
    }

    public record RecalculationResult(int targetLength, List<VarintPatch> patches) {}

    public record VarintPatch(
        int frameId,
        int parentFrameId,
        int frameStartOffset,
        int lengthFieldOffset,
        int oldBodyLength,
        int newBodyLength,
        int oldVarintSize,
        int newVarintSize
    ) {}

    /**
     * Mutable frame state for bottom-up propagation.
     */
    private static final class FrameState {
        private final int frameId;
        private final int parentFrameId;
        private final int frameStartOffset;
        private final int lengthFieldOffset;
        private final int oldBodyLength;
        private final int localNewBodyLength;
        private int effectiveNewBodyLength;

        private FrameState(int frameId,
                           int parentFrameId,
                           int frameStartOffset,
                           int lengthFieldOffset,
                           int oldBodyLength,
                           int localNewBodyLength) {
            this.frameId = frameId;
            this.parentFrameId = parentFrameId;
            this.frameStartOffset = frameStartOffset;
            this.lengthFieldOffset = lengthFieldOffset;
            this.oldBodyLength = oldBodyLength;
            this.localNewBodyLength = localNewBodyLength;
            this.effectiveNewBodyLength = localNewBodyLength;
        }

        private int encodedSizeDelta() {
            return (varintSize(effectiveNewBodyLength) + effectiveNewBodyLength)
                - (varintSize(oldBodyLength) + oldBodyLength);
        }
    }
}
