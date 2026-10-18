#!/usr/bin/env python3
"""Specification arithmetic and abstract graph probes, no container implementation.

Source: docs/architecture/container-format-v1.md at
54e40f38d8622fc683672a96b643bf243c59b3b6. Python 3 stdlib only.
"""
import json
import struct


def head(major, value):
    if value < 24:
        return bytes([(major << 5) | value])
    for ceiling, ai, fmt in [(256, 24, '>B'), (65536, 25, '>H'),
                             (2**32, 26, '>I'), (2**64, 27, '>Q')]:
        if value < ceiling:
            return bytes([(major << 5) | ai]) + struct.pack(fmt, value)
    raise ValueError(value)


def cbor(value):
    if value is None:
        return b'\xf6'
    if isinstance(value, bool):
        return b'\xf5' if value else b'\xf4'
    if isinstance(value, int):
        return head(0, value) if value >= 0 else head(1, -1-value)
    if isinstance(value, bytes):
        return head(2, len(value)) + value
    if isinstance(value, str):
        data = value.encode('utf-8', errors='strict')
        return head(3, len(data)) + data
    if isinstance(value, list):
        return head(4, len(value)) + b''.join(map(cbor, value))
    raise TypeError(type(value))


def byte_id(n):
    return b'\xff' * n


def logical(kind=5, ordinal=2**32-1):
    return [byte_id(32), byte_id(32), kind, byte_id(16), ordinal]


def physical(length=16384):
    return [2**40-8, length, byte_id(32)]


def levels(items, fanout):
    pages = []
    while True:
        items = (items + fanout - 1) // fanout
        pages.append(items)
        if items == 1:
            return pages


def reach(roots, edges):
    seen = set()
    todo = list(roots)
    while todo:
        node = todo.pop()
        if node not in seen:
            seen.add(node)
            todo.extend(edges.get(node, []))
    return seen


def main():
    metadata = {}
    metadata['catalogue_leaf_page'] = 16 + len(cbor([
        byte_id(16), [[byte_id(32), 2**40-8, 1048576+92, byte_id(32)]] * 128]))
    metadata['catalogue_branch_page'] = 16 + len(cbor([
        byte_id(16), [[byte_id(32), physical()]] * 128]))
    metadata['objects_leaf_or_branch_plaintext'] = len(cbor([
        1, 7, [[byte_id(16), logical(2, 0)]] * 64]))
    metadata['children_leaf_plaintext'] = len(cbor([
        2, 0, [[[byte_id(16), byte_id(16)], None]] * 64]))
    metadata['children_branch_plaintext'] = len(cbor([
        2, 7, [[[byte_id(16), byte_id(16)], logical(2, 0)]] * 64]))
    metadata['chunk_list_leaf_plaintext'] = len(cbor([
        0, [[524287, logical(5, 524287)]] * 64]))
    metadata['chunk_list_branch_plaintext'] = len(cbor([
        7, [[524287, logical(3, 0)]] * 64]))
    root = [1, 1, 2**63-1, byte_id(16), byte_id(16),
            logical(2, 0), logical(2, 0), logical(3, 0), 2**64-1,
            [[byte_id(16), byte_id(16)]] * 256]
    metadata['zone_root_max_plaintext'] = len(cbor(root))
    descriptor = [byte_id(16), 2, 2**63-1, 1, 4, 1, 1, 19, 65536, 4, 1,
                  byte_id(16), byte_id(12), byte_id(48), 'x'*512,
                  logical(1, 0), byte_id(32), physical()]
    descriptor_size = len(cbor(descriptor))
    for label, size in metadata.items():
        assert size <= 16384, (label, size)
    assert descriptor_size <= 4092
    header_fields = [8, 2, 2, 4, 8, 8, 16, 16, 1, 7, 4096, 4096, 57240, 32]
    assert sum(header_fields) == 65536
    record_fields = [8, 2, 1, 1, 16, 32, 12, 4]
    assert sum(record_fields) == 76
    assert 512*2**30 // 2**20 == 524288
    assert len(levels(524288, 64)) <= 8
    assert len(levels(16777216, 128)) <= 8
    # This is an abstract future-schema example, not a currently valid v1 fixture.
    true_edges = {'root': ['object'], 'object': ['opaque-body'],
                  'opaque-body': ['future-external-chunk']}
    known_edges = {'root': ['object'], 'object': ['opaque-body']}
    true_reachable = reach(['root'], true_edges)
    v1_reachable = reach(['root'], known_edges)
    unknown_loss = sorted(true_reachable - v1_reachable)
    assert unknown_loss == ['future-external-chunk']
    output = {
        'sha': '54e40f38d8622fc683672a96b643bf243c59b3b6',
        'header_size': sum(header_fields), 'record_header_size': sum(record_fields),
        'record_max_raw_length': 1048576+92,
        'logical_ref_max_size': len(cbor(logical())),
        'descriptor_max_cbor_size': descriptor_size,
        'page_sizes_bytes': metadata,
        'levels_leaf_to_root': {'media_512GiB_chunks_fanout64': levels(524288, 64),
                               'objects_1million_fanout64': levels(1000000, 64),
                               'catalogue_16million_fanout128': levels(16777216, 128)},
        'endianness_vectors': {'fixed_u32_256_LE': struct.pack('<I', 256).hex(),
                               'cbor_uint_256_BE': cbor(256).hex()},
        'opaque_future_graph': {'true_reachable': sorted(true_reachable),
                                'v1_known_reachable': sorted(v1_reachable),
                                'missing_if_collected': unknown_loss},
        'folder_payload_undefined_alternatives': {
            'null': cbor(None).hex(), 'empty_array': cbor([]).hex()},
    }
    print(json.dumps(output, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
