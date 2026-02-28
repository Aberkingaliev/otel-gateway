#!/usr/bin/env python3
"""Generate a realistic OTLP ExportTraceServiceRequest protobuf payload.

Outputs raw protobuf binary to stdout (pipe to file).
The payload contains N resource spans, each with M spans and realistic attributes
including tenant_id for masking rules to match.

Usage:
    python3 gen-otlp-payload.py [--spans-per-resource 100] [--resources 1] > payload.bin
"""
import struct
import sys
import os
import time
import random
import hashlib

def encode_varint(value):
    result = bytearray()
    while value > 0x7F:
        result.append((value & 0x7F) | 0x80)
        value >>= 7
    result.append(value & 0x7F)
    return bytes(result)

def encode_field_varint(field_num, value):
    return encode_varint((field_num << 3) | 0) + encode_varint(value)

def encode_field_fixed64(field_num, value):
    return encode_varint((field_num << 3) | 1) + struct.pack('<Q', value)

def encode_field_bytes(field_num, data):
    tag = encode_varint((field_num << 3) | 2)
    return tag + encode_varint(len(data)) + data

def encode_field_string(field_num, s):
    return encode_field_bytes(field_num, s.encode('utf-8'))

def make_any_value_string(s):
    """AnyValue with string_value (field 1)"""
    return encode_field_string(1, s)

def make_any_value_int(n):
    """AnyValue with int_value (field 2)"""
    return encode_field_varint(2, n)

def make_key_value(key, value_bytes):
    """KeyValue: key (field 1) + value (field 2)"""
    return encode_field_string(1, key) + encode_field_bytes(2, value_bytes)

def make_trace_id():
    return os.urandom(16)

def make_span_id():
    return os.urandom(8)

def make_span(span_index, trace_id):
    """Span proto (opentelemetry.proto.trace.v1.Span)"""
    buf = bytearray()
    # field 1: trace_id (bytes)
    buf += encode_field_bytes(1, trace_id)
    # field 2: span_id (bytes)
    buf += encode_field_bytes(2, make_span_id())
    # field 4: parent_span_id (bytes) - empty for root
    # field 5: name (string)
    ops = ['GET /api/v1/users', 'POST /api/v1/orders', 'DB SELECT users',
           'redis GET session', 'grpc.client.call', 'http.request',
           'queue.publish', 'cache.lookup', 'auth.validate', 'payment.process']
    buf += encode_field_string(5, ops[span_index % len(ops)])
    # field 6: kind (enum) - SPAN_KIND_CLIENT=3
    buf += encode_field_varint(6, 3)
    # field 7: start_time_unix_nano (fixed64)
    now_ns = int(time.time() * 1e9)
    buf += encode_field_fixed64(7, now_ns - random.randint(1000000, 50000000))
    # field 8: end_time_unix_nano (fixed64)
    buf += encode_field_fixed64(8, now_ns)
    # field 9: attributes (repeated KeyValue)
    attrs = [
        make_key_value('tenant_id', make_any_value_string('black_list')),
        make_key_value('env', make_any_value_string('soak')),
        make_key_value('http.method', make_any_value_string('GET')),
        make_key_value('http.status_code', make_any_value_int(200)),
        make_key_value('http.url', make_any_value_string(f'https://api.example.com/v1/resource/{span_index}')),
        make_key_value('user.id', make_any_value_string(f'user-{span_index % 1000:04d}')),
        make_key_value('user.email', make_any_value_string(f'user{span_index % 1000}@example.com')),
        make_key_value('net.peer.ip', make_any_value_string(f'10.0.{span_index % 256}.{(span_index * 7) % 256}')),
        make_key_value('service.version', make_any_value_string('2.1.0')),
    ]
    for attr in attrs:
        buf += encode_field_bytes(9, attr)
    # field 10: dropped_attributes_count
    # field 11: events (repeated) - skip for benchmark
    # field 15: status
    status = encode_field_varint(1, 1)  # STATUS_CODE_OK
    buf += encode_field_bytes(15, status)
    return bytes(buf)

def make_scope_spans(num_spans, trace_id):
    """ScopeSpans proto"""
    buf = bytearray()
    # field 1: scope (InstrumentationScope)
    scope = encode_field_string(1, 'com.acme.finops.gateway') + encode_field_string(2, '1.0.0')
    buf += encode_field_bytes(1, scope)
    # field 2: spans (repeated Span)
    for i in range(num_spans):
        span_bytes = make_span(i, trace_id)
        buf += encode_field_bytes(2, span_bytes)
    return bytes(buf)

def make_resource_spans(resource_index, spans_per_resource):
    """ResourceSpans proto"""
    buf = bytearray()
    # field 1: resource (Resource)
    resource_attrs = bytearray()
    attrs = [
        make_key_value('service.name', make_any_value_string(f'payment-service-{resource_index}')),
        make_key_value('service.namespace', make_any_value_string('finops')),
        make_key_value('tenant_id', make_any_value_string('black_list')),
        make_key_value('deployment.environment', make_any_value_string('production')),
        make_key_value('host.name', make_any_value_string(f'node-{resource_index:03d}.internal')),
        make_key_value('k8s.pod.name', make_any_value_string(f'payment-{resource_index}-abc123')),
    ]
    for attr in attrs:
        resource_attrs += encode_field_bytes(1, attr)  # Resource.attributes field 1
    buf += encode_field_bytes(1, bytes(resource_attrs))
    # field 2: scope_spans (repeated ScopeSpans)
    trace_id = make_trace_id()
    scope_spans = make_scope_spans(spans_per_resource, trace_id)
    buf += encode_field_bytes(2, scope_spans)
    # field 3: schema_url
    buf += encode_field_string(3, 'https://opentelemetry.io/schemas/1.21.0')
    return bytes(buf)

def make_export_request(num_resources, spans_per_resource):
    """ExportTraceServiceRequest proto"""
    buf = bytearray()
    for r in range(num_resources):
        rs = make_resource_spans(r, spans_per_resource)
        buf += encode_field_bytes(1, rs)  # field 1: resource_spans (repeated)
    return bytes(buf)

def main():
    import argparse
    parser = argparse.ArgumentParser(description='Generate OTLP trace protobuf payload')
    parser.add_argument('--spans-per-resource', type=int, default=100,
                        help='Number of spans per resource (default: 100)')
    parser.add_argument('--resources', type=int, default=1,
                        help='Number of resource spans (default: 1)')
    parser.add_argument('--output', type=str, default=None,
                        help='Output file (default: stdout)')
    args = parser.parse_args()

    payload = make_export_request(args.resources, args.spans_per_resource)

    if args.output:
        with open(args.output, 'wb') as f:
            f.write(payload)
        size_kb = len(payload) / 1024
        print(f'Generated {args.resources} resource(s) x {args.spans_per_resource} spans = '
              f'{args.resources * args.spans_per_resource} total spans', file=sys.stderr)
        print(f'Payload size: {len(payload)} bytes ({size_kb:.1f} KB)', file=sys.stderr)
        print(f'Written to: {args.output}', file=sys.stderr)
    else:
        sys.stdout.buffer.write(payload)
        print(f'Payload: {len(payload)} bytes, '
              f'{args.resources * args.spans_per_resource} spans', file=sys.stderr)

if __name__ == '__main__':
    main()
