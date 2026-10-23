#!/usr/bin/env python3
"""Actual APK negatives on owned copies: privacy host ACL and ordinary-release mapping binding."""
import ast
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile
import struct
root=Path(__file__).resolve().parents[2]
source=ast.parse((root/'tools/p6a/check-isolation.py').read_text())
function=next(n for n in source.body if isinstance(n,ast.FunctionDef) and n.name=='change_attribute')
exec(compile(ast.Module(body=[function],type_ignores=[]),'<reviewed AXMl helper>','exec'))
target=root/'app/build/outputs/apk/p1Release/app-p1Release.apk'
release=root/'app/build/outputs/apk/release/app-release-unsigned.apk'
original=[hashlib.sha256(p.read_bytes()).hexdigest() for p in (target,release)]
java=str(Path(os.environ['JAVA_HOME'])/'bin/java')
classes=str(root/'app/build/p1-apk-verifier/classes')
arguments=(root/'app/build/reports/p6b2-runtime/audit-arguments.txt').read_text().splitlines()
with tempfile.TemporaryDirectory(prefix='p6b2-privacy-negative-') as owned:
    temporary=Path(owned); bad=temporary/'bad-host-acl.apk'
    with zipfile.ZipFile(target) as src,zipfile.ZipFile(bad,'w') as dst:
        manifest=change_attribute(src.read('AndroidManifest.xml'),'activity','com.fantadrom.copperscroll.p6b2harness.P6b2SessionActivity','exported',1)
        for e in src.infolist(): dst.writestr(e,manifest if e.filename=='AndroidManifest.xml' else src.read(e))
    args=arguments.copy(); args[0]=str(bad); args[3]=str(temporary/'audit.txt'); args[6]=str(temporary/'p6b2-runtime')
    p=subprocess.run([java,'-cp',classes,'VerifyRuntimeApks',*args],capture_output=True,text=True,timeout=120)
    assert p.returncode!=0 and 'P6b2 host must be nonexported Activity' in p.stderr,p.stdout+p.stderr
    mapping=root/'app/build/outputs/mapping/release/mapping.txt'
    report=temporary/'ordinary.properties'
    p=subprocess.run([java,'-cp',classes,'VerifyRuntimeApks','--ordinary-release',str(release),str(mapping),str(report)],capture_output=True,text=True,timeout=120)
    assert p.returncode==0,p.stdout+p.stderr
    wrong=temporary/'wrong-mapping.txt'
    lines=mapping.read_text().splitlines()
    lines=[('# pg_map_id: '+'0'*64) if line.startswith('# pg_map_id: ') else line for line in lines]
    wrong.write_text('\n'.join(lines)+'\n')
    p=subprocess.run([java,'-cp',classes,'VerifyRuntimeApks','--ordinary-release',str(release),str(wrong),str(report)],capture_output=True,text=True,timeout=120)
    assert p.returncode!=0 and 'mapping does not match actual DEX marker' in p.stderr,p.stdout+p.stderr
    assert not report.exists(), 'Stale ordinary release static PASS survived failed audit'
assert original==[hashlib.sha256(p.read_bytes()).hexdigest() for p in (target,release)]
print('P6b2 actual APK privacy ACL + ordinary release mapping: 2 negatives refused; originals unchanged; Android NOT_RUN')
