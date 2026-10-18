#!/usr/bin/env python3
"""Independent stdlib fixture author, not imported by production. --check verifies frozen bytes."""
import argparse, hashlib, json, pathlib, struct
ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / 'app/src/test/resources/p1'
def sha(b): return hashlib.sha256(b).digest()
def cbor(v):
    def head(m,n):
        if n < 24: return bytes([m*32+n])
        for ai,fmt,limit in [(24,'B',256),(25,'>H',65536),(26,'>I',2**32),(27,'>Q',2**64)]:
            if n < limit: return bytes([m*32+ai])+struct.pack(fmt,n)
        raise ValueError('integer limit')
    if v is None: return b'\xf6'
    if isinstance(v,bool): return b'\xf5' if v else b'\xf4'
    if isinstance(v,int): return head(0,v) if v>=0 else head(1,-1-v)
    if isinstance(v,bytes): return head(2,len(v))+v
    if isinstance(v,str):
        b=v.encode('utf-8'); return head(3,len(b))+b
    return head(4,len(v))+b''.join(cbor(x) for x in v)
def marker(v):
    if isinstance(v,bytes): return {'bytes':v.hex()}
    if isinstance(v,list): return [marker(x) for x in v]
    return v
Z=bytes(range(1,17)); O=bytes(range(17,33)); ID=bytes(range(1,33)); ID2=bytes(range(33,65)); H=bytes(range(32)); ZERO=bytes(32)
def logical(record=ID,kind=1,owner=Z,ordinal=0,hash=H): return [record,hash,kind,owner,ordinal]
def physical(offset=131072,length=16384,hash=H): return [offset,length,hash]
def desc(root=logical(),catalogue=physical(),zone=Z,role=1):
    return [zone,role,7,1,3,1,1,19,65536,3,1,bytes(range(16)),bytes(range(12)),bytes(range(48)),'synthetic public hint',root,ZERO,catalogue]
def record(rid=ID,zone=Z,kind=1,n=3):
    return b'CSREC001'+struct.pack('<HBB',1,kind,0)+zone+rid+bytes(range(12))+struct.pack('<I',n)+bytes(n+16)
def page(level,entries,zone=Z):
    p=cbor([zone,entries]); assert len(p)<=16368
    return b'CSCAT001'+bytes([level,0,0,0])+struct.pack('<I',len(p))+p+bytes(16368-len(p))
def header(length,zones=None,generation=5):
    if zones is None: zones=[desc()]
    b=bytearray(65536); b[:8]=b'CSCRL001'; struct.pack_into('<HHIQQ',b,8,1,1,0,generation,length)
    b[32:48]=O;b[48:64]=Z;b[64]=len(zones)
    for i,d in enumerate(zones):
        p=cbor(d);o=72+i*4096;struct.pack_into('<I',b,o,len(p));b[o+4:o+4+len(p)]=p
    b[65504:]=sha(b[:65504]);return bytes(b)
def synthetic(two=False,cycle=False,overlap=False):
    # Two leaves and a branch; all ciphertext is arbitrary, never AEAD verified.
    raw1=record();raw2=record(ID2,kind=3)
    rec1off=180224;rec2off=180320
    leaf1=page(0,[[ID,rec1off,len(raw1),sha(raw1)]])
    leaf2=page(0,[[ID2,rec1off if overlap else rec2off,len(raw2),sha(raw2)]])
    branch=page(1,[[ID,physical(147456,hash=sha(leaf1))],[ID2,physical(163840,hash=sha(leaf2))]])
    if cycle: branch=page(1,[[ID,physical(131072,hash=ZERO)]])
    length=rec2off+len(raw2)
    d=desc(logical(hash=sha(raw1)),physical(hash=sha(branch)))
    h=header(length,[d]); b=bytearray(h+bytes(65536));b+=branch+leaf1+leaf2
    assert len(b)==rec1off
    b+=raw1+bytes(rec2off-rec1off-len(raw1))+raw2
    return bytes(b)
def two_zone():
    raw1=record();raw2=record(ID2,zone=O)
    o1=163840;o2=163936;length=o2+len(raw2)
    p1=page(0,[[ID,o1,len(raw1),sha(raw1)]]);p2=page(0,[[ID2,o2,len(raw2),sha(raw2)]],O)
    d1=desc(logical(hash=sha(raw1)),physical(131072,hash=sha(p1)))
    d2=desc(logical(ID2,owner=O,hash=sha(raw2)),physical(147456,hash=sha(p2)),O,2)
    h=header(length,[d1,d2]);return h+h+p1+p2+raw1+bytes(o2-o1-len(raw1))+raw2

def expected():
    fixtures={}
    def add(name,v): fixtures[name]={'fields':marker(v),'hex':cbor(v).hex()}
    add('physical',physical());add('logical',logical());add('descriptor',desc())
    add('descriptor-logical',desc()[:16])
    add('settings-default',[1,False,None,True,True,1,True,1])
    add('settings-unknown',[1,False,None,True,True,4294967295,False,99])
    folder=[1,O,1,Z,'Empty',4294967295,'folder',False,None,-1,0,1,1,None]
    add('folder',folder)
    note=[1,O,2,Z,'',4294967295,'future_note',False,None,1,2,3,1,[1,15,[logical(kind=4,owner=O)],4294967295]]
    add('object-unknown-icon',note)
    add('note-rich',[1,[['A😀e\u0301',None],[' todo ',False],['',True],['',None]],[[1,3,True,False,False,None],[2,8,False,True,True,4294901760]]])
    add('root',[1,1,7,Z,O,logical(kind=2),logical(ID2,kind=2),logical(kind=3),18446744073709551615,[[O,None]]])
    add('index-objects',[1,0,[[O,logical(kind=3,owner=O)]]])
    add('index-children',[2,0,[[[Z,O],None]]])
    add('index-branch',[1,1,[[O,logical(kind=2)]]])
    add('chunk-leaf',[0,[[0,logical(kind=5,owner=O)]]])
    add('chunk-branch',[1,[[0,logical(kind=3,owner=O)]]])
    add('media-object',[1,O,3,Z,'image',4294967295,'image',False,None,1,2,3,1,[1,'image/png',1,1,logical(kind=3,owner=O)]])
    bins={'superblock.bin':header(196608),'record.bin':record(),'catalogue-leaf.bin':page(0,[[ID,180224,95,H]]),
          'catalogue-branch.bin':page(1,[[ID,physical(147456)]]),'synthetic.cscroll':synthetic(),
          'cycle.cscroll':synthetic(cycle=True),'overlap.cscroll':synthetic(overlap=True),'two-zones.cscroll':two_zone()}
    return fixtures,bins

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--check',action='store_true');a=ap.parse_args()
    f,b=expected(); b.update({name+'.hex':(v['hex']+'\n').encode() for name,v in f.items()}); b['golden.json']=(json.dumps(f,ensure_ascii=False,indent=2)+'\n').encode()
    old=json.loads((ROOT/'docs/architecture/reconciliation-materials/wire-fixtures.json').read_text())['fixtures']
    b['reconciliation.tsv']=''.join('\t'.join([x['id'],x['schema'],str(x['schema_valid']),x['cbor_hex']])+'\n' for x in old).encode()
    vectors=json.loads((ROOT/'docs/architecture/reconciliation-materials/s1-vectors.json').read_text())['fixtures']
    lines=[]
    for x in vectors:
        if 'utf16le_hex' in x:
            data=bytes.fromhex(x['utf16le_hex']);text=''.join(f'{int.from_bytes(data[i:i+2],"little"):04x}' for i in range(0,len(data),2))
        else:text=x.get('text','').encode('utf-16-be').hex()
        lines.append('\t'.join([x['id'],x['method'],text,','.join(map(str,x.get('hits',[]))),x['expected'].get('hex','InvalidSecret')]))
    b['s1.tsv']=('\n'.join(lines)+'\n').encode()
    negatives=[('settings','981801f4f6f5f501f501','CorruptData'),('settings','8801f4f6f5f501f50100','CorruptData'),
        ('settings','9f01f4f6f5f501f501ff','CorruptData'),('settings','8802f4f6f5f501f501','UnsupportedVersion'),
        ('physical',cbor([2**64-8,16384,H]).hex(),'CorruptData'),('physical',cbor([131073,16384,H]).hex(),'CorruptData'),
        ('physical',cbor([131072,2**32,H]).hex(),'CorruptData')]
    folder=[1,O,1,Z,'Empty',4294967295,'folder',False,None,-1,0,1,1,[]]
    negatives.append(('object',cbor(folder).hex(),'CorruptData'))
    b['negative.tsv']=''.join('\t'.join(x)+'\n' for x in negatives).encode()
    for name,value in b.items():
        p=OUT/name
        if a.check: assert p.read_bytes()==value,name
        else: p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(value)
    print('independent fixed fixtures: PASS',len(b))
if __name__=='__main__': main()
