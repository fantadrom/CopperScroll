#!/usr/bin/env python3
"""Independent app-local metadata v1/v2 byte author; portable container is unchanged."""
from pathlib import Path
import argparse
import hashlib
import struct

ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / 'app/src/test/resources/p5b'

def cbor(value):
    def head(major, n):
        if n < 24: return bytes([(major << 5) | n])
        for ai, size in [(24, 1), (25, 2), (26, 4), (27, 8)]:
            if n < 1 << (8 * size): return bytes([(major << 5) | ai]) + n.to_bytes(size, 'big')
        raise ValueError('integer limit')
    if value is None: return b'\xf6'
    if isinstance(value, int): return head(0, value)
    if isinstance(value, bytes): return head(2, len(value)) + value
    if isinstance(value, str):
        raw = value.encode('utf-8'); return head(3, len(raw)) + raw
    return head(4, len(value)) + b''.join(cbor(item) for item in value)

def fixture(version):
    fp = ['synthetic:/source', bytes([1]) * 16, bytes([2]) * 16, 3, 131072, bytes([3]) * 32, bytes([4]) * 32]
    if version == 2: fp += [['p5b-fixture', 'source-identity']]
    journal = [bytes([5]) * 16, 'compact' if version == 1 else 'rekey', [bytes([6]) * 16], fp,
               None, None, 'Prepared', fp, 'Pending']
    body = cbor([version, fp, journal]); frame = b'CSLOCAL1' + struct.pack('<I', len(body)) + body
    return frame + hashlib.sha256(frame).digest()

def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--check', action='store_true'); args = parser.parse_args()
    DEST.mkdir(parents=True, exist_ok=True)
    for version, name in [(1, 'local-v1-prepared.hex'), (2, 'local-v2-rekey-prepared.hex')]:
        path = DEST / name; expected = fixture(version).hex() + '\n'
        if args.check: assert path.read_text() == expected, name
        else: path.write_text(expected)
    print('P5b independent local metadata fixtures: v1/v2 PASS (not a container format change)')

if __name__ == '__main__': main()
