#!/usr/bin/env python3
"""Independent stdlib-only CBOR author for P4. Never rewrites P1/P2 fixtures."""
import argparse
import copy
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def head(major, n):
    if n < 24:
        return bytes([major * 32 + n])
    for width, ai in [(1, 24), (2, 25), (4, 26), (8, 27)]:
        if n < 1 << (8 * width):
            return bytes([major * 32 + ai]) + n.to_bytes(width, 'big')
    raise ValueError('integer range')


def encode(value):
    if value is None:
        return b'\xf6'
    if isinstance(value, bool):
        return b'\xf5' if value else b'\xf4'
    if isinstance(value, int):
        return head(0 if value >= 0 else 1, value if value >= 0 else -1-value)
    if isinstance(value, bytes):
        return head(2, len(value)) + value
    if isinstance(value, str):
        b = value.encode('utf-8', 'strict')
        return head(3, len(b)) + b
    if isinstance(value, list):
        return head(4, len(value)) + b''.join(encode(x) for x in value)
    raise TypeError(type(value))


def fixtures():
    params = [['0000 0000', False, False, 32768], None, [None, False, False, 32768],
              None, None, ['mm/yy'], [3], None, None, None]
    values = ['0012', 'A\n\U0001f642\ne\u0301', 'e\u0301', 'invalid URL', 'invalid email',
              [2000, 2, 29], [1, 9, 5], '12345678901234567890123456789012345678', ['-12340', 3], False]
    fields = [[bytes([i])*16, i, 'f'+str(i), params[i-1], values[i-1]] for i in range(1, 11)]
    card = [1, fields]
    body = encode(card)
    object_id = bytes([0x41])*16
    logical = [bytes([0x52])*32, bytes([0x63])*32, 4, object_id, 0]
    obj = [2, object_id, 6, bytes([0x74])*16, 'Карточка', 0xffffffff, 'future-card',
           False, None, -1, 2, 3, 7, [1, len(body), [logical], False]]
    good = [('card_all_fields', body), ('card_empty', encode([1, []])), ('object_card_1_2', encode(obj))]
    bad = []
    for name, change, failure in [
        ('unknown_field', lambda x: x[1][0].__setitem__(1, 11), 'UnsupportedVersion'),
        ('duplicate_id', lambda x: x[1][1].__setitem__(0, x[1][0][0]), 'ValidationError'),
        ('invalid_date', lambda x: x[1][5].__setitem__(4, [2019, 2, 29]), 'ValidationError'),
        ('integer_leading_zero', lambda x: x[1][7].__setitem__(4, '0012'), 'ValidationError'),
        ('duplicate_pattern', lambda x: x[1][6].__setitem__(4, [1, 1]), 'ValidationError'),
        ('decimal_scale', lambda x: x[1][8].__setitem__(4, ['1', 19]), 'CorruptData'),
        ('mask_unknown', lambda x: x[1][0][3].__setitem__(0, '00A00'), 'ValidationError'),
    ]:
        modified = copy.deepcopy(card)
        change(modified)
        bad.append((name, encode(modified), failure))
    bad += [('card_version', encode([2, []]), 'UnsupportedVersion'),
            ('noncanonical_version', b'\x82\x18\x01\x80', 'CorruptData'),
            ('indefinite', b'\x9f\x01\x80\xff', 'CorruptData'),
            ('trailing', encode([1, []])+b'\x00', 'CorruptData'),
            ('huge_declared_array', b'\x82\x01\x99\xff\xff', 'CorruptData')]
    return good, bad


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--write', action='store_true')
    p.add_argument('--check', action='store_true')
    a = p.parse_args()
    good, bad = fixtures()
    files = {'golden.tsv': ''.join(n+'\t'+b.hex()+'\n' for n, b in good).encode(),
             'negative.tsv': ''.join(n+'\t'+b.hex()+'\t'+failure+'\n' for n, b, failure in bad).encode()}
    for base in ['app/src/test/resources/p4', 'app/src/androidTest/assets/p4']:
        for name, content in files.items():
            path = ROOT/base/name
            if a.write:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
            else:
                assert path.read_bytes() == content, str(path)
    provenance = {'author': 'independent Python stdlib positional CBOR encoder',
                  'container_versions': ['1.1 unchanged legacy fixtures', '1.2 P4'],
                  'good_count': len(good), 'negative_count': len(bad),
                  'generator_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  'files': {n: hashlib.sha256(b).hexdigest() for n, b in files.items()}}
    path = ROOT/'tools/p4/provenance.json'
    content = (json.dumps(provenance, ensure_ascii=False, indent=2)+'\n').encode()
    if a.write:
        path.write_bytes(content)
    else:
        assert path.read_bytes() == content
    print(f'P4 independent fixtures: {len(good)} positive, {len(bad)} negative; JVM/Android bytes identical; PASS')


if __name__ == '__main__':
    main()
