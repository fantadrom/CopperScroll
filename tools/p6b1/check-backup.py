#!/usr/bin/env python3
"""Mutate copies of actual APK AXML; never edit/install original signed artifacts."""
import hashlib
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import re
import zipfile
import sys
profile=sys.argv[1] if len(sys.argv)>1 else "p6b1"
assert profile in ("p6b1","p6b2")

root=Path(__file__).resolve().parents[2]
release=root/'app/build/outputs/apk/release/app-release-unsigned.apk'
target=root/'app/build/outputs/apk/p1Release/app-p1Release.apk'
java=str(Path(os.environ['JAVA_HOME'])/'bin/java')
classes=str(root/'app/build/p1-apk-verifier/classes')
hashes=[hashlib.sha256(p.read_bytes()).hexdigest() for p in (release,target)]

def attribute(blob,attribute,value):
    b=bytearray(blob); strings=[]; offset=struct.unpack_from('<H',b,2)[0]
    while offset<len(b):
        kind,header,size=struct.unpack_from('<HHI',b,offset)
        if kind==1:
            count,_,flags,start=struct.unpack_from('<4I',b,offset+8)
            def length(p,utf8):
                first=b[p] if utf8 else struct.unpack_from('<H',b,p)[0]; p+=1 if utf8 else 2
                mask=128 if utf8 else 32768
                if first&mask:
                    second=b[p] if utf8 else struct.unpack_from('<H',b,p)[0]
                    first=((first&(mask-1))<<(8 if utf8 else 16))|second; p+=1 if utf8 else 2
                return first,p
            for i in range(count):
                p=offset+start+struct.unpack_from('<I',b,offset+header+4*i)[0]
                n,p=length(p,bool(flags&256))
                if flags&256: n,p=length(p,True)
                else: n*=2
                strings.append(bytes(b[p:p+n]).decode('utf-8' if flags&256 else 'utf-16le'))
        elif kind==0x102 and strings[struct.unpack_from('<I',b,offset+20)[0]]=='application':
            start,stride,count=struct.unpack_from('<3H',b,offset+24)
            for i in range(count):
                p=offset+16+start+stride*i
                if strings[struct.unpack_from('<I',b,p+4)[0]]==attribute:
                    struct.pack_into('<I',b,p+16,value(strings) if callable(value) else value); return bytes(b)
        offset+=size
    raise AssertionError('application/allowBackup absent')

aapt2=Path(os.environ['ANDROID_HOME'])/'build-tools/37.0.0/aapt2'
def resources(apk):
    dump=subprocess.check_output([str(aapt2),'dump','resources',str(apk)],text=True,timeout=30)
    result={}
    for name in ('backup_rules','data_extraction_rules'):
        match=re.search(r'^\s*resource (0x[0-9a-fA-F]+) xml/'+name+r'\s*$',dump,re.M)
        assert match
        block=re.split(r'^\s*resource ',dump[match.end():],maxsplit=1,flags=re.M)[0]
        paths=re.findall(r'\(file\) (res/\S+) type=XML',block)
        assert len(paths)==1
        result[name]=(int(match[1],16),paths[0])
    return result

arguments=(root/f'app/build/reports/{profile}-runtime/audit-arguments.txt').read_text().splitlines()
baseline=subprocess.run([java,'-cp',classes,'VerifyRuntimeApks',*arguments],capture_output=True,text=True,timeout=120)
assert baseline.returncode==0,baseline.stdout+baseline.stderr
test=Path(arguments[1]); hashes.append(hashlib.sha256(test.read_bytes()).hexdigest())
with tempfile.TemporaryDirectory(prefix='p6b1-backup-') as owned:
    for apk in (release,target,test):
        refs=resources(apk)
        for change in ('allowBackup','missing_rules','domain','wrong_reference', *(() if apk==test else ('wrong_agent',))):
            changed=Path(owned)/(apk.stem+'-'+change+'.apk')
            with zipfile.ZipFile(apk) as src,zipfile.ZipFile(changed,'w') as dst:
                for entry in src.infolist():
                    if change=='missing_rules' and entry.filename==refs['data_extraction_rules'][1]: continue
                    data=src.read(entry)
                    if change=='allowBackup' and entry.filename=='AndroidManifest.xml': data=attribute(data,'allowBackup',1)
                    if change=='wrong_agent' and entry.filename=='AndroidManifest.xml': data=attribute(data,'backupAgent',lambda pool: next(i for i,s in enumerate(pool) if s.endswith('.MainActivity')))
                    if change=='wrong_reference' and entry.filename=='AndroidManifest.xml': data=attribute(data,'fullBackupContent',refs['data_extraction_rules'][0])
                    if change=='domain' and entry.filename==refs['backup_rules'][1]:
                        # Equal encoded length preserves the AXML pool, exposing an unsupported domain.
                        updated=data.replace(b'sharedpref',b'wrong_____').replace('sharedpref'.encode('utf-16le'),'wrong_____'.encode('utf-16le'))
                        assert updated!=data; data=updated
                    dst.writestr(entry,data)
            if apk==test:
                args=arguments.copy(); args[1]=str(changed); args[3]=str(Path(owned)/'audit.txt'); args[6]=str(Path(owned)/f'{profile}-runtime')
            else: args=['--p6a-isolation',str(changed if apk==release else release),str(changed if apk==target else target)]
            p=subprocess.run([java,'-cp',classes,'VerifyRuntimeApks',*args],capture_output=True,text=True,timeout=120)
            expected={'allowBackup':'allowBackup must be false','missing_rules':'Missing AXML:','domain':'Invalid/duplicate backup exclusion','wrong_reference':'Manifest backup reference points to wrong resource','wrong_agent':'Refusal backup agent missing from manifest'}[change]
            assert p.returncode!=0 and expected in p.stderr,(change,p.stdout,p.stderr)
assert hashes==[hashlib.sha256(p.read_bytes()).hexdigest() for p in (release,target,test)]
print('P6b1 actual AXML backup audit: 14 negative APKs refused; originals unchanged; Android NOT_RUN')
