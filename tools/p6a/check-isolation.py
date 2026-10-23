#!/usr/bin/env python3
"""Negative build-output audits. Never modifies or installs signed APKs."""
import hashlib
import os
from pathlib import Path
import subprocess
import struct
import tempfile
import zipfile
import sys

root = Path(__file__).resolve().parents[2]
profile=sys.argv[1] if len(sys.argv)>1 else 'p6a'
assert profile in ('p6a','p6b1','p6b2')
release = root / 'app/build/outputs/apk/release/app-release-unsigned.apk'
harness = root / 'app/build/outputs/apk/p1Release/app-p1Release.apk'
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java')
classes = str(root / 'app/build/p1-apk-verifier/classes')
original = [hashlib.sha256(p.read_bytes()).hexdigest() for p in (release, harness)]

def run(a, b, success):
    p = subprocess.run([java, '-cp', classes, 'VerifyRuntimeApks', '--p6a-isolation', str(a), str(b)], capture_output=True, text=True, timeout=60)
    assert (p.returncode == 0) == success, p.stdout + p.stderr

run(release, harness, True)
with tempfile.TemporaryDirectory(prefix='p6a-isolation-') as owned:
    path = Path(owned) / 'mutated.apk'
    with zipfile.ZipFile(release) as src, zipfile.ZipFile(harness) as h, zipfile.ZipFile(path, 'w') as dst:
        for entry in src.infolist():
            dst.writestr(entry, h.read('AndroidManifest.xml') if entry.filename == 'AndroidManifest.xml' else src.read(entry))
    run(path, harness, False)
    run(harness, harness, False)
    run(release, release, False)
    with zipfile.ZipFile(release) as src, zipfile.ZipFile(path, 'w') as dst:
        for entry in src.infolist():
            if not (entry.filename.startswith('classes') and entry.filename.endswith('.dex')):
                dst.writestr(entry, src.read(entry))
    run(path, harness, False)
assert original == [hashlib.sha256(p.read_bytes()).hexdigest() for p in (release, harness)]
print('P6a isolation audit: positive + 4 negatives PASS; APKs unchanged; Android NOT_RUN')

# The external provider runs under the test APK's own UID/class loader. Its manifest
# must be present as well as its DEX definition; generic combined linkage is insufficient.
arguments = (root / f'app/build/reports/{profile}-runtime/audit-arguments.txt').read_text().splitlines()
test_apk = Path(arguments[1])
test_hash = hashlib.sha256(test_apk.read_bytes()).hexdigest()
with tempfile.TemporaryDirectory(prefix='p6a-external-audit-') as owned:
    temporary = Path(owned)
    mutated = temporary / 'external-authority.apk'
    with zipfile.ZipFile(test_apk) as src, zipfile.ZipFile(mutated, 'w') as dst:
        manifest = src.read('AndroidManifest.xml')
        modified = manifest
        for encoding in ('utf-8', 'utf-16le'):
            a = '.test.p6a.external.documents'.encode(encoding)
            modified = modified.replace(a, '.test.p6a.external.invalidxx'.encode(encoding))
        assert modified != manifest
        for entry in src.infolist():
            dst.writestr(entry, modified if entry.filename == 'AndroidManifest.xml' else src.read(entry))
    args = arguments.copy()
    args[1] = str(mutated)
    args[3] = str(temporary / 'audit.txt')
    args[6] = str(temporary / f'{profile}-runtime')
    p = subprocess.run([java, '-cp', classes, 'VerifyRuntimeApks', *args], capture_output=True, text=True, timeout=120)
    assert p.returncode != 0 and 'External grant provider manifest/authority missing' in p.stderr, p.stdout + p.stderr
assert test_hash == hashlib.sha256(test_apk.read_bytes()).hexdigest()
print('P6a external-provider manifest negative audit PASS; APK unchanged; Android NOT_RUN')

def change_attribute(blob, tag, component, attribute, value):
    """Change a typed AXML attribute only; preserve the real DEX and every other attribute."""
    b = bytearray(blob)
    strings = []
    offset = struct.unpack_from('<H', b, 2)[0]
    while offset < len(b):
        kind, header, size = struct.unpack_from('<HHI', b, offset)
        if kind == 1:
            count, _, flags, start = struct.unpack_from('<4I', b, offset + 8)
            def length(p, utf8):
                n = b[p] if utf8 else struct.unpack_from('<H', b, p)[0]
                p += 1 if utf8 else 2
                mask = 128 if utf8 else 32768
                if n & mask:
                    second = b[p] if utf8 else struct.unpack_from('<H', b, p)[0]
                    n = ((n & (mask - 1)) << (8 if utf8 else 16)) | second
                    p += 1 if utf8 else 2
                return n, p
            for i in range(count):
                p = offset + start + struct.unpack_from('<I', b, offset + header + 4 * i)[0]
                n, p = length(p, bool(flags & 256))
                if flags & 256:
                    n, p = length(p, True)
                else:
                    n *= 2
                strings.append(bytes(b[p:p+n]).decode('utf-8' if flags & 256 else 'utf-16le'))
        elif kind == 0x102 and strings[struct.unpack_from('<I', b, offset + 20)[0]] == tag:
            start, stride, count = struct.unpack_from('<3H', b, offset + 24)
            attrs = {}
            for i in range(count):
                p = offset + 16 + start + stride * i
                attrs[strings[struct.unpack_from('<I', b, p + 4)[0]]] = p
            name = attrs.get('name')
            if name is not None and strings[struct.unpack_from('<I', b, name + 16)[0]] == component:
                struct.pack_into('<I', b, attrs[attribute] + 16, value(strings) if callable(value) else value)
                return bytes(b)
        offset += size
    raise AssertionError('AXML mutation target absent')

for which, tag, component, attribute, value, expected in (
    (0, 'permission', 'com.fantadrom.copperscroll.p1runtime.p6a.GRANT_CONTROL', 'protectionLevel', 0,
     'Grant owner permission must be signature-only'),
    (1, 'service', 'com.fantadrom.copperscroll.storage.runtime.ExternalGrantOwnerService', 'permission',
     lambda pool: pool.index('android.permission.MANAGE_DOCUMENTS'), 'Grant owner channel missing or unprotected'),
):
    source = Path(arguments[which])
    with tempfile.TemporaryDirectory(prefix='p6a-owner-acl-') as owned:
        temporary = Path(owned); mutated = temporary / 'bad-acl.apk'
        with zipfile.ZipFile(source) as src, zipfile.ZipFile(mutated, 'w') as dst:
            manifest = change_attribute(src.read('AndroidManifest.xml'), tag, component, attribute, value)
            for entry in src.infolist():
                dst.writestr(entry, manifest if entry.filename == 'AndroidManifest.xml' else src.read(entry))
        args = arguments.copy(); args[which] = str(mutated)
        args[3] = str(temporary / 'audit.txt'); args[6] = str(temporary / f'{profile}-runtime')
        p = subprocess.run([java, '-cp', classes, 'VerifyRuntimeApks', *args], capture_output=True, text=True, timeout=120)
        assert p.returncode != 0 and expected in p.stderr, p.stdout + p.stderr
assert original == [hashlib.sha256(p.read_bytes()).hexdigest() for p in (release, harness)]
assert test_hash == hashlib.sha256(test_apk.read_bytes()).hexdigest()
print('P6a grant owner ACL: 2 negative audits PASS; signed APKs unchanged; Android NOT_RUN')
