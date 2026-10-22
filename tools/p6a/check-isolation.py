#!/usr/bin/env python3
"""Negative build-output audits. Never modifies or installs signed APKs."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

root = Path(__file__).resolve().parents[2]
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
arguments = (root / 'app/build/reports/p6a-runtime/audit-arguments.txt').read_text().splitlines()
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
    args[6] = str(temporary / 'p6a-runtime')
    p = subprocess.run([java, '-cp', classes, 'VerifyRuntimeApks', *args], capture_output=True, text=True, timeout=120)
    assert p.returncode != 0 and 'External grant provider manifest/authority missing' in p.stderr, p.stdout + p.stderr
assert test_hash == hashlib.sha256(test_apk.read_bytes()).hexdigest()
print('P6a external-provider manifest negative audit PASS; APK unchanged; Android NOT_RUN')
