package com.acme.finops.gateway.wire.mutate;

import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.policy.PolicyDecision;

/**
 * Builds a {@link MutationPlan} describing how a packet should be mutated
 * according to a {@link PolicyDecision}.
 *
 * <p>Implementations should be stateless and thread-safe.
 */
public interface MutationPlanner {
    /**
     * Plans mutations for the given packet envelope based on the policy decision.
     *
     * @param envelope  the source packet to inspect (not modified)
     * @param decision  the policy evaluation result driving mutation choices
     * @return a plan describing all required in-place and reframe operations
     */
    MutationPlan plan(PacketRef envelope, PolicyDecision decision);
}
