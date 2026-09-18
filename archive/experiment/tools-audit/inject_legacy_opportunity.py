#!/usr/bin/env python3
"""Insert one pre-R1.2 active opportunity into an offline semantic snapshot for the legacy-adoption LIVE gate."""
import json, sys

if len(sys.argv) != 11:
    raise SystemExit("usage: inject_legacy_opportunity.py <semantic.json> <id> <dimension> <x> <y> <z> <block> <seen_x> <seen_y> <seen_z>")
path, oid, dim, x, y, z, block, sx, sy, sz = sys.argv[1:]
root=json.load(open(path,encoding="utf-8"))
ops=root.setdefault("resource_opportunities",[])
if any(o.get("id")==oid for o in ops):
    raise SystemExit("id already present")
ops.append({
    "id":oid,
    "dimension":dim,
    "x":int(x),"y":int(y),"z":int(z),
    "block":block,
    "seen_x":int(sx),"seen_y":int(sy),"seen_z":int(sz),
    "status":"ACTIONABLE",
    "blocked_reason":"",
    "required_tool":"minecraft:iron_pickaxe",
    "last_seen_game_time":0,
    "state_since_game_time":0,
    "state_x":int(sx),"state_y":int(sy),"state_z":int(sz),
    "pickup_baseline":-1,
})
with open(path,"w",encoding="utf-8",newline="\n") as f:
    json.dump(root,f,separators=(",",":"),ensure_ascii=False)
print(f"injected legacy active opportunity id={oid} at {x},{y},{z}")
