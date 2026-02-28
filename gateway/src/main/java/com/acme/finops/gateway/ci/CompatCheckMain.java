package com.acme.finops.gateway.ci;

import com.acme.finops.gateway.compat.CompatInput;
import com.acme.finops.gateway.compat.CompatResult;
import com.acme.finops.gateway.compat.DefaultCompatAnalyzer;
import com.acme.finops.gateway.memory.PacketDescriptor;
import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.memory.PacketRefImpl;
import com.acme.finops.gateway.transport.api.ProtocolKind;
import com.acme.finops.gateway.transport.api.SignalKind;
import com.acme.finops.gateway.util.JsonCodec;
import com.acme.finops.gateway.wire.SchemaId;
import com.fasterxml.jackson.databind.JsonNode;

import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CompatCheckMain {
    private CompatCheckMain() {
    }

    public static void main(String[] args) throws Exception {
        Path input = args.length > 0 ? Path.of(args[0]) : Path.of("ci-input/compat-input.json");
        if (!Files.exists(input)) {
            throw new IllegalArgumentException("compat input file not found: " + input);
        }
        JsonNode root = JsonCodec.readTree(Files.readString(input));
        long requestId = requiredLong(root, "requestId");
        JsonNode sourceNode = requiredObject(root, "sourceSchema");
        JsonNode targetNode = requiredObject(root, "targetSchema");

        SchemaId source = new SchemaId(
            requiredText(sourceNode, "namespace"),
            requiredText(sourceNode, "name"),
            requiredText(sourceNode, "version")
        );
        SchemaId target = new SchemaId(
            requiredText(targetNode, "namespace"),
            requiredText(targetNode, "name"),
            requiredText(targetNode, "version")
        );

        byte[] payload = new byte[]{0x0A, 0x00};
        PacketRef ref = new PacketRefImpl(
            requestId,
            new PacketDescriptor(requestId, requestId, SignalKind.TRACES, ProtocolKind.OTLP_HTTP_PROTO, 0, payload.length, System.nanoTime()),
            MemorySegment.ofArray(payload),
            0,
            payload.length
        );
        try {
            CompatInput compatInput = new CompatInput(requestId, source, target, ref);
            CompatResult result = new DefaultCompatAnalyzer().analyze(compatInput);
            System.out.println("compatResult=" + result);
            if (result instanceof CompatResult.Incompatible || result instanceof CompatResult.RewriteRequired) {
                System.exit(2);
            }
        } finally {
            ref.release();
        }
    }

    private static JsonNode requiredObject(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("missing required object field: " + field);
        }
        return node;
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException("missing required text field: " + field);
        }
        return node.asText().trim();
    }

    private static long requiredLong(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isNumber()) {
            throw new IllegalArgumentException("missing required long field: " + field);
        }
        return node.asLong();
    }
}

