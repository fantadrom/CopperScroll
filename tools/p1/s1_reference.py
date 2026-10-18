#!/usr/bin/env python3
"""Independent S1 adapter for synthetic fixtures. Normalization/repertoire from unicodedata2, never system UCD."""
import json, sys
import unicodedata2 as u
assert u.unidata_version=='16.0.0'
def repertoire(s,points,bytes):
    if len(s)>points or len(s.encode('utf-8',errors='strict'))>bytes:raise ValueError()
    if any(ord(c) in (0,10,13) or u.category(c) in ('Cn','Cs') for c in s):raise ValueError()
def encode(f):
    method=f['method'];s=f.get('text','')
    if 'utf16le_hex' in f:s=bytes.fromhex(f['utf16le_hex']).decode('utf-16-le',errors='surrogatepass')
    if method in ('pin4','pin6'):
        if len(s)!=(4 if method=='pin4' else 6) or any(c<'0' or c>'9' for c in s):raise ValueError()
    elif method=='text':
        repertoire(s,1024,4096);s=u.normalize('NFC',s);repertoire(s,256,1024)
        if not s:raise ValueError()
    else:
        path=[]
        for endpoint in f['hits']:
            if endpoint not in range(1,10):raise ValueError()
            if endpoint in path:continue
            if path:
                a=path[-1]-1;b=endpoint-1
                x=(a%3+b%3);y=(a//3+b//3)
                if x%2==0 and y%2==0:
                    midpoint=y//2*3+x//2+1
                    if midpoint not in path:path.append(midpoint)
            path.append(endpoint)
        if not 4<=len(path)<=9:raise ValueError()
        s=''.join(map(str,path))
    return s.encode('utf-8',errors='strict').hex()
def main():
    request=json.load(sys.stdin);results=[]
    for f in request['fixtures']:
        try:results.append({'id':f['id'],'status':'ok','hex':encode(f)})
        except (ValueError,UnicodeError):results.append({'id':f['id'],'status':'InvalidSecret'})
    print(json.dumps({'unicode_version':u.unidata_version,'results':results}))
if __name__=='__main__':main()
