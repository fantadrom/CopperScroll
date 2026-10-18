#!/usr/bin/env python3
"""Static fixture check, not a container codec or a Unicode16 normalizer.

Optional --adapter COMMAND... receives s1-vectors.json on stdin and must return
{"unicode_version":"16.0.0","results":[{"id":...,"status":"ok","hex":...}, ...]}.
Negative cases use status InvalidSecret. The adapter must implement pinned S1,
not merely echo expected values. Independent implementations run separately.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import unicodedata

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent


def unbox(v):
    if isinstance(v, dict) and set(v) == {'bytes'}:
        return bytes.fromhex(v['bytes'])
    if isinstance(v, list):
        return [unbox(x) for x in v]
    return v


def decode_fixture(raw):
    """Independent limited decoder for these short static fixtures only."""
    pos = 0

    def read(depth=0):
        nonlocal pos
        assert depth <= 16 and pos < len(raw)
        first = raw[pos]
        pos += 1
        major, ai = first >> 5, first & 31
        if major == 7:
            assert ai in (20, 21, 22)
            return {20: False, 21: True, 22: None}[ai]
        assert major in (0, 1, 2, 3, 4)
        if ai < 24:
            n = ai
        else:
            assert ai in (24, 25, 26, 27)
            width = {24: 1, 25: 2, 26: 4, 27: 8}[ai]
            assert pos + width <= len(raw)
            n = int.from_bytes(raw[pos:pos + width], 'big')
            pos += width
            assert n >= {24: 24, 25: 256, 26: 65536, 27: 2**32}[ai]
        if major == 0:
            return n
        if major == 1:
            return -1 - n
        if major in (2, 3):
            assert pos + n <= len(raw)
            data = raw[pos:pos + n]
            pos += n
            return data if major == 2 else data.decode('utf-8', errors='strict')
        assert n <= 256
        return [read(depth + 1) for _ in range(n)]

    value = read()
    assert pos == len(raw)
    return value


def fixture_schema_valid(kind, value):
    # Only schema cases exercised by the fixtures; not a production validator.
    if kind == 'folder':
        return (isinstance(value, list) and len(value) == 14 and value[0] == 1
                and value[2] == 1 and value[6] == 'folder'
                and value[7] is False and value[8] is None and value[13] is None)
    if kind == 'settings':
        return (isinstance(value, list) and len(value) == 8 and value[0] == 1
                and all(type(value[i]) is bool for i in (1, 3, 4, 6))
                and all(type(value[i]) is int and 0 <= value[i] < 2**32
                        for i in (5, 7)))
    if kind == 'note-icon':
        return isinstance(value, str) and re.fullmatch(r'[a-z][a-z0-9_-]{0,31}', value) is not None
    raise ValueError(kind)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--adapter', nargs=argparse.REMAINDER)
    args = ap.parse_args()
    spec = importlib.util.spec_from_file_location(
        'historic_size_encoder', ROOT.parent / 'review-materials/format_probe.py')
    encoder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(encoder)
    wire = json.loads((ROOT / 'wire-fixtures.json').read_text())
    assert wire['container_version'] == '1.1'
    for f in wire['fixtures']:
        data = bytes.fromhex(f['cbor_hex'])
        value = unbox(f['value'])
        assert decode_fixture(data) == value, f['id']
        assert encoder.cbor(value) == data, f['id']
        assert fixture_schema_valid(f['schema'], value) == f['schema_valid'], f['id']
    default = next(f for f in wire['fixtures'] if f['id'] == 'settings-default')
    assert default['cbor_hex'] == '8801f4f6f5f501f501'
    assert struct.pack('<H', 1).hex() == '0100'  # containerMinor1
    s1text = (ROOT / 's1-vectors.json').read_text()
    s1 = json.loads(s1text)
    assert s1['unicode_version'] == '16.0.0' and s1['uax15_revision'] == 56
    ids = [f['id'] for f in s1['fixtures']]
    assert len(ids) == len(set(ids))
    for f in s1['fixtures']:
        expected = f['expected']
        if expected['status'] == 'ok':
            assert expected['normalized'].encode('utf-8').hex() == expected['hex'], f['id']
    target = next(f for f in s1['fixtures'] if f['id'] == 'unicode16-decomposition')
    host_nfc = unicodedata.normalize('NFC', target['text']).encode().hex()
    adapter_status = 'NOT RUN: no pinned Unicode16 implementation supplied'
    if args.adapter:
        # Do not send expected answers to the adapter, only input requests.
        request = {'unicode_version': '16.0.0', 'fixtures': [
            {k: v for k, v in f.items() if k != 'expected'} for f in s1['fixtures']]}
        result = subprocess.run(args.adapter, input=json.dumps(request), text=True,
                                capture_output=True, timeout=60, check=True)
        answer = json.loads(result.stdout)
        assert answer['unicode_version'] == '16.0.0'
        actual = {v['id']: v for v in answer['results']}
        assert len(actual) == len(answer['results']) and set(actual) == set(ids)
        for f in s1['fixtures']:
            got, expected = actual[f['id']], f['expected']
            assert got['status'] == expected['status'], f['id']
            if expected['status'] == 'ok':
                assert got['hex'] == expected['hex'], f['id']
        adapter_status = 'PASS: 21 S1 vectors, not full Unicode conformance'
    print(json.dumps({
        'wire_fixtures_passed': len(wire['fixtures']),
        's1_static_expectations_checked': len(s1['fixtures']),
        'host_unicode_version': unicodedata.unidata_version,
        'host_pair_nfc_hex': host_nfc,
        'normative_unicode16_pair_hex': target['expected']['hex'],
        'host_is_exact_required_data_version': unicodedata.unidata_version == '16.0.0',
        'pinned_s1_execution': adapter_status,
        'full_unicode16_conformance': 'NOT RUN; mandatory P1 gate',
        'scope': 'small fixture consistency; no Android, AEAD or engine tested',
    }, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
