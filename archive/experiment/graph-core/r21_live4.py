# -*- coding: utf-8 -*-
"""LIVE-R21-4 driver: mine -> strip drops -> observe MINED_PENDING_PICKUP + typed reason."""
import json, subprocess, sys, time, urllib.request, urllib.error

TOKEN = "mc1ca-isolated-token-0123456789abcdefghijklmnop"
BASE = "http://127.0.0.1:8765"
OREO = "22,115,6"
OPP = "ore_9c3e86fbc8cf3d57bd12bd20f5ad50f4"
LEASE_FILE = ".r21-lease-live4.json"

def call(method, path, lease=None, body=None, headers=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    if lease: req.add_header("X-Control-Token", lease)
    for k, v in (headers or {}).items(): req.add_header(k, v)
    data = None
    if body is not None:
        data = body.encode(); req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data, timeout=20) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as fault:
        raise RuntimeError("%s %s -> %s %s" % (method, path, fault.code, fault.read().decode()[:200]))

import atexit

def lease():
    seq = int(time.time() * 1000) % 1000000
    conflict = None
    for attempt in range(12):
        try:
            data = call("POST", "/v1/lease", headers={"X-Owner-Id": "r21-l4-%d" % seq})["data"]
            break
        except RuntimeError as fault:
            conflict = fault; time.sleep(5)
    else:
        raise conflict
    json.dump({"token": data["token"]}, open(LEASE_FILE, "w", encoding="utf-8"))
    def _cleanup():
        try: call("DELETE", "/v1/lease", data["token"])
        except Exception: pass
    atexit.register(_cleanup)
    return data["token"]

def rcon(cmd):
    return subprocess.run([sys.executable, "rcon.py", cmd], capture_output=True, text=True).stdout

def ore_is_air():
    return "Seed" in rcon("execute if block %s air run seed" % OREO)

def opp_state():
    d = call("GET", "/v1/observe", tok)["data"]["observation"]["semantic_world"]["resource_opportunities"]
    for o in d:
        if o["id"] == OPP:
            return o["status"], o.get("blocked_reason", "")
    return None, ""

tok = lease()
print("lease ok")
call("GET", "/v1/observe", tok)
ex = call("POST", "/v1/executions/mine_opportunity", tok, json.dumps({"id": OPP}),
          {"X-Request-Id": "r21-l4-mine-%d" % int(time.time())})["data"]["execution_id"]
print("execution:", ex)

stripped = False
deadline = time.time() + 240
last = None
while time.time() < deadline:
    time.sleep(1)
    if not stripped and ore_is_air():
        print("  ore broken -> stripping item drops:", rcon("kill @e[type=item]").strip()[:60])
        stripped = True
    st = call("GET", "/v1/executions/" + ex, tok)["data"]
    if st["state"] != last:
        last = st["state"]
        print("  state=%s p=%.2f reason=%s" % (st["state"], st["progress"], st["reason"][:160]))
    if st["state"] in ("completed", "failed", "cancelled", "outcome_unknown"):
        print("STOPPING at", st["state"], "|", st["reason"])
        break

try:
    st, why = opp_state()
    print("registry state:", st, "|", why)
    print("registry file:")
except Exception as e:
    print("registry probe failed:", e)

# stop tracking; release lease
try: call("DELETE", "/v1/lease", tok)
except Exception: pass
print("stripped drops:", stripped)
