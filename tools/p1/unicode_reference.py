#!/usr/bin/env python3
"""Independent CPython/unicodedata2 normalization, not ICU. Full UAX15 invariants + Part1 exclusion sweep."""
import gzip, hashlib, json, pathlib, sys
import unicodedata2 as u
assert u.unidata_version == '16.0.0'
root=pathlib.Path(__file__).resolve().parents[2]
raw=gzip.decompress((root/'app/src/test/resources/p1/NormalizationTest-16.0.0.txt.gz').read_bytes())
assert hashlib.sha256(raw).hexdigest()=='d811971453e7075e1ad56fb1b301eece5aa80757b81f6156e74a1bfb3ae5ceb1'
part1=set();part='';rows=0;checks=0
for line in raw.decode().splitlines():
    line=line.split('#')[0].strip()
    if not line: continue
    if line.startswith('@'):part=line;continue
    cols=[''.join(chr(int(c,16)) for c in field.split()) for field in line.split(';')[:5]]
    if part.startswith('@Part1'):part1.add(ord(cols[0]))
    for form,expected in [('NFC',[cols[1]]*3+[cols[3]]*2),('NFD',[cols[2]]*3+[cols[4]]*2),('NFKC',[cols[3]]*5),('NFKD',[cols[4]]*5)]:
        for c,e in zip(cols,expected): assert u.normalize(form,c)==e,(rows,form);checks+=1
    rows+=1
sweep=0
for cp in range(0x110000):
    if cp in part1 or 0xD800<=cp<=0xDFFF:continue
    s=chr(cp)
    for form in ('NFC','NFD','NFKC','NFKD'):assert u.normalize(form,s)==s,(cp,form);sweep+=1
print(json.dumps({'implementation':'unicodedata2','version':u.unidata_version,'rows':rows,'row_assertions':checks,'identity_assertions':sweep,'status':'PASS'}))
