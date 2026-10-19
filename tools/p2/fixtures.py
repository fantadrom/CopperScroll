#!/usr/bin/env python3
"""Independent OpenSSL-backed crypto oracle; NEVER imported by production/JVM tests.
Only synthetic fixed inputs. --check regenerates and compares frozen expected bytes.
"""
import argparse
import hashlib
import importlib.util
from pathlib import Path
import struct
from cryptography.hazmat.primitives import hashes, hmac
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.argon2 import Argon2id
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('p1_fixture_author', ROOT/'tools/p1/fixtures.py')
p1 = importlib.util.module_from_spec(spec); spec.loader.exec_module(p1)

def hkdf(key, salt, info):
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=salt, info=info).derive(key)

def compose():
    container = bytes(range(1,17)); zone = bytes(range(17,33)); owner = zone
    zdk = bytes(range(64,96)); salt = bytes(range(16)); wrap_nonce = bytes(range(16,28))
    record_id = bytes(range(32,64)); nonce = bytes(range(32,44)); secret = b'0012'
    kraw = Argon2id(salt=salt, length=32, iterations=2, lanes=1, memory_cost=16384).derive(secret)
    kwrap = hkdf(kraw, container, b'CS-wrap-key-v1\0'+zone)
    wrap_aad = p1.cbor(['CS-wrap-v1', container, zone, 1, 1, 1, 1, 1, 19, 16384, 2, 1, salt])
    wrapped = AESGCM(kwrap).encrypt(wrap_nonce, zdk, wrap_aad)
    plaintext = b'synthetic P2 root'
    header = b'CSREC001'+struct.pack('<HBB',1,1,0)+zone+record_id+nonce+struct.pack('<I',len(plaintext))
    record_info = b'CS-data-v1\0'+zone+record_id+b'\x01'+owner+struct.pack('<I',0)
    krecord = hkdf(zdk,container,record_info)
    aad = b'CS-record-v1\0'+struct.pack('<H',1)+container+header+owner+struct.pack('<I',0)
    raw = header+AESGCM(krecord).encrypt(nonce,plaintext,aad)
    root = [record_id,hashlib.sha256(raw).digest(),1,owner,0]
    logical = [zone,1,7,1,1,1,1,19,16384,2,1,salt,wrap_nonce,wrapped,'synthetic public hint',root]
    logical_bytes = p1.cbor(logical)
    kdesc = hkdf(zdk,container,b'CS-desc-v1\0'+zone)
    m = hmac.HMAC(kdesc,hashes.SHA256()); m.update(b'CS-logical-v1\0'+container+logical_bytes); mac=m.finalize()
    descriptor = p1.cbor(logical+[mac,[131072,16384,bytes(32)]])
    values = dict(container=container,zone=zone,owner=owner,zdk=zdk,salt=salt,wrapNonce=wrap_nonce,
        recordId=record_id,nonce=nonce,secret=secret,kraw=kraw,kwrap=kwrap,wrapAAD=wrap_aad,
        wrappedZDK=wrapped,kdesc=kdesc,logicalBytes=logical_bytes,logicalMAC=mac,
        descriptor=descriptor,recordInfo=record_info,krecord=krecord,recordAAD=aad,rawRecord=raw,
        plaintext=plaintext)
    # A non-root owner and nonzero ordinal detect u32 endian/order mistakes hidden by root ordinal0.
    chunk_owner=bytes(range(33,49)); chunk_id=bytes(range(96,128)); chunk_nonce=bytes(range(64,76))
    chunk_ordinal=struct.pack('<I',0x01020304); chunk_plain=b'synthetic P2 chunk'
    chunk_header=b'CSREC001'+struct.pack('<HBB',1,4,0)+zone+chunk_id+chunk_nonce+struct.pack('<I',len(chunk_plain))
    chunk_info=b'CS-data-v1\0'+zone+chunk_id+b'\x04'+chunk_owner+chunk_ordinal
    chunk_key=hkdf(zdk,container,chunk_info)
    chunk_aad=b'CS-record-v1\0'+struct.pack('<H',1)+container+chunk_header+chunk_owner+chunk_ordinal
    values.update(chunkOwner=chunk_owner,chunkOrdinal=chunk_ordinal,chunkId=chunk_id,chunkNonce=chunk_nonce,
        chunkPlaintext=chunk_plain,chunkInfo=chunk_info,chunkKey=chunk_key,chunkAAD=chunk_aad,
        chunkRaw=chunk_header+AESGCM(chunk_key).encrypt(chunk_nonce,chunk_plain,chunk_aad))
    return '# Synthetic only; independent cryptography50/OpenSSL oracle, container1.1\n'+''.join(k+'='+v.hex()+'\n' for k,v in values.items())

def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--check',action='store_true'); args=parser.parse_args()
    expected=compose().encode('ascii')
    paths=[ROOT/'app/src/test/resources/p2/crypto.properties', ROOT/'app/src/androidTest/assets/p2/crypto.properties']
    for path in paths:
        if args.check: assert path.read_bytes()==expected, path
        else: path.parent.mkdir(parents=True,exist_ok=True); path.write_bytes(expected)
    print('Independent composite fixture: 31 fixed fields, JVM/Android bytes identical; PASS')

if __name__=='__main__': main()
