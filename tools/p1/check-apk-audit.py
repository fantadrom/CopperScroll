#!/usr/bin/env python3
"""Negative checks for the build-output audit. No device and no changes to the signed APKs."""
import hashlib
import os
import re
from pathlib import Path
import subprocess
import struct
import tempfile
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / 'app/build/outputs/apk/p1Release/app-p1Release.apk'
TEST = ROOT / 'app/build/outputs/apk/androidTest/p1Release/app-p1Release-androidTest.apk'
DEBUG = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
CLASSES = ROOT / 'app/build/p1-apk-verifier/classes'
GOLDEN = ROOT / 'app/src/test/resources/p1/settings-default.hex'
ARGUMENTS = Path(os.environ.get('P1_AUDIT_ARGUMENTS', str(ROOT / 'app/build/reports/p1-runtime/audit-arguments.txt')))
JAVA = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def rewrite(source, dest, replacements):
    with zipfile.ZipFile(source) as src, zipfile.ZipFile(dest, 'w') as out:
        for entry in src.infolist():
            out.writestr(entry, replacements.get(entry.filename, src.read(entry)))

def hierarchy_dex():
    """Minimal class_defs parser fixture: public Child outside its package-private Parent's package.

    Not an installable DEX (no code/checksum); exercises the host structural guard only.
    """
    names = [b'Lfixture/Parent;', b'LChild;']
    strings, types, classes = 112, 120, 128
    data = bytearray(classes + 2 * 32)
    data[:8] = b'dex\n039\0'
    for i, name in enumerate(names):
        struct.pack_into('<I', data, strings + i * 4, len(data))
        data += bytes([len(name)]) + name + b'\0'
        struct.pack_into('<I', data, types + i * 4, i)
        struct.pack_into('<IIIIIIII', data, classes + i * 32, i, i, 0xffffffff if i == 0 else 0, 0, 0, 0, 0, 0)
    for offset, value in [(32, len(data)), (40, 0x12345678), (56, 2), (60, strings),
                          (64, 2), (68, types), (96, 2), (100, classes)]:
        struct.pack_into('<I', data, offset, value)
    return bytes(data)

def tracing_mutations(dex, descriptor, method):
    """Mutate actual class/member tables in host-only copies; originals are never installed/changed."""
    def u32(offset): return struct.unpack_from('<I', dex, offset)[0]
    def u16(offset): return struct.unpack_from('<H', dex, offset)[0]
    def leb(offset):
        value, shift = 0, 0
        while True:
            b = dex[offset]; offset += 1; value |= (b & 127) << shift; shift += 7
            if not b & 128: return value, offset
    strings, positions = [], []
    for i in range(u32(56)):
        _, pos = leb(u32(u32(60) + i * 4)); positions.append(pos)
        strings.append(dex[pos:dex.index(b'\0', pos)].decode('utf-8', errors='replace'))
    types = [strings[u32(u32(68) + i * 4)] for i in range(u32(64))]
    trace = types.index(descriptor)
    def checksum(data):
        data[12:32] = hashlib.sha1(data[32:]).digest()
        struct.pack_into('<I', data, 8, zlib.adler32(data[12:])); return bytes(data)
    pos = positions[strings.index(descriptor)]
    renamed = 'L' + 'Z' * (len(descriptor) - 2) + ';'
    assert renamed not in strings
    missing_class = bytearray(dex); missing_class[pos:pos+len(descriptor)] = renamed.encode()
    selected = None
    for i in range(u32(88)):
        row = u32(92) + i * 8
        if u16(row) == trace and strings[u32(row + 4)] == method:
            selected = i; break
    assert selected is not None
    missing_method = bytearray(dex)
    struct.pack_into('<I', missing_method, u32(92) + selected * 8 + 4, strings.index('toString'))
    inaccessible = bytearray(dex); changed = False
    for i in range(u32(96)):
        row = u32(100) + i * 32
        if u32(row) != trace: continue
        cursor = u32(row + 24); counts = []
        for _ in range(4):
            count, cursor = leb(cursor); counts.append(count)
        for group, count in enumerate(counts):
            index = 0
            for _ in range(count):
                delta, cursor = leb(cursor); index += delta
                flag_pos = cursor; flags, cursor = leb(cursor)
                if group >= 2:
                    _, cursor = leb(cursor)
                    if index == selected:
                        assert flags == 9 and cursor > flag_pos
                        inaccessible[flag_pos] = 10  # public static -> private static
                        changed = True
    assert changed
    return checksum(missing_class), checksum(missing_method), checksum(inaccessible)

def main():
    original = {p: digest(p) for p in [TARGET, TEST, DEBUG]}
    arguments = ARGUMENTS.read_text().splitlines()
    assert len(arguments) >= 9
    (ROOT / 'work').mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='p1-apk-audit-', dir=ROOT/'work') as tmp:
        tmp = Path(tmp)
        def rejected(label, target, test, expected, wrong_mapping=False):
            rest = arguments[4:]
            # Keep the selected audit profile while isolating generated diagnostics.
            rest[2] = str(tmp/Path(arguments[6]).name)
            if wrong_mapping: rest[1] = rest[0]
            result = subprocess.run([JAVA, '-cp', str(CLASSES), 'VerifyRuntimeApks',
                str(target), str(test), str(GOLDEN), str(tmp/'audit.txt'), *rest],
                text=True, capture_output=True, timeout=30)
            assert result.returncode != 0 and expected in result.stderr, label + '\n' + result.stderr[:2500]
            print(label + ': correctly rejected')
        rejected('debug substituted for release-derived target', DEBUG, TEST, 'APK R8 markers do not match')
        with zipfile.ZipFile(TARGET) as target:
            dex = target.read('classes.dex')
            data = bytearray(target.read('com/ibm/icu/impl/data/icudata/nfc.nrm')); data[-1] ^= 1
        duplicate = tmp/'duplicate-backend.apk'
        rewrite(TEST, duplicate, {})
        with zipfile.ZipFile(duplicate, 'a') as out:
            out.writestr('classes99.dex', dex, compress_type=zipfile.ZIP_DEFLATED)
        rejected('production backend injected into test APK', TARGET, duplicate, 'second backend implementation')
        corrupt = tmp/'corrupt-unicode.apk'
        rewrite(TARGET, corrupt, {'com/ibm/icu/impl/data/icudata/nfc.nrm': bytes(data)})
        rejected('changed pinned NFC data', corrupt, TEST, 'Pinned ICU data hash mismatch')
        inaccessible = tmp/'inaccessible-superclass.apk'
        rewrite(TEST, inaccessible, {})
        with zipfile.ZipFile(inaccessible, 'a') as out:
            out.writestr('classes98.dex', hierarchy_dex())
        rejected('public child outside nonpublic superclass package', TARGET, inaccessible, 'Inaccessible nonpublic superclass')
        mapping = Path(arguments[4]).read_text()
        block = re.search(r'^androidx.tracing.Trace -> ([^:]+):\n(.*?)(?=^[^\s#])', mapping, re.M | re.S)
        assert block
        descriptor = 'L' + block[1].replace('.', '/') + ';'
        method = re.search(r'void forceEnableAppTracing\(\).*? -> (\w+)', block[2])[1]
        with zipfile.ZipFile(TARGET) as target:
            variants = tracing_mutations(target.read('classes.dex'), descriptor, method)
        for label, mutated, expected in zip(['required runner class missing', 'required runner method missing', 'runner method made inaccessible'],
                variants, ['Missing runtime class', 'Missing runtime method', 'Inaccessible target member']):
            path = tmp/(label.replace(' ', '-') + '.apk')
            rewrite(TARGET, path, {'classes.dex': mutated})
            rejected(label, path, TEST, expected)
        rejected('target mapping substituted for test mapping', TARGET, TEST, 'APK R8 markers do not match', wrong_mapping=True)
    assert all(digest(p) == value for p, value in original.items())
    print('APK audit negative checks: PASS; original signed APKs unchanged; runtime NOT RUN')

if __name__ == '__main__':
    main()
