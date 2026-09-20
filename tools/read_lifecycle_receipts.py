#!/usr/bin/env python3
# Minimal offline BridgeJournal lifecycle receipt extractor.
import json, struct, sys, zlib

path=sys.argv[1]
data=open(path,"rb").read()
assert data[:8] == b"AIBODY01", data[:8]
pos=44
out=[]
while pos < len(data):
    if pos+4>len(data): break
    n=struct.unpack_from(">i",data,pos)[0]
    body=data[pos+4:pos+4+n]
    crc=struct.unpack_from(">i",data,pos+4+n)[0]
    if (zlib.crc32(body)&0xffffffff) != (crc&0xffffffff):
        raise SystemExit(f"crc mismatch at {pos}")
    seq,ts,count=struct.unpack_from(">qqi",body,0)
    p=20
    fields={}
    for _ in range(count):
        klen=struct.unpack_from(">i",body,p)[0]; p+=4
        k=body[p:p+klen].decode(); p+=klen
        vlen=struct.unpack_from(">i",body,p)[0]; p+=4
        v=body[p:p+vlen].decode(); p+=vlen
        fields[k]=v
    if fields.get("kind") in {
        "resource_opportunity_birth",
        "resource_opportunity_consumed",
        "resource_opportunity_stale",
    }:
        out.append({"seq":seq,"ts":ts,"fields":fields})
    pos += n+8
for row in out:
    print(json.dumps(row,ensure_ascii=False))
