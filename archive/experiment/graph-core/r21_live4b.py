# -*- coding: utf-8 -*-
"""LIVE-R21-4 rerun (acceptance gap): pending -> restart -> recovery SUCCESS with inventory delta."""
import json, os, subprocess, sys, time, urllib.request, urllib.error

TOKEN = "mc1ca-isolated-token-0123456789abcdefghijklmnop"
BASE = "http://127.0.0.1:8765"
ORE = "52,116,6"
OPP = "ore_54b7d886a09038af93f225a2403bf314"
LEASE_FILE = ".r21-lease-l4b.json"

def call(method, path, lease=None, body=None, headers=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    if lease: req.add_header("X-Control-Token", lease)
    for k, v in (headers or {}).items(): req.add_header(k, v)
    data = None
    if body is not None:
        data = body.encode(); req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data, timeout=25) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as fault:
        raise RuntimeError("%s %s -> %s %s" % (method, path, fault.code, fault.read().decode()[:160]))

def rcon(cmd):
    return subprocess.run([sys.executable, "rcon.py", cmd], capture_output=True, text=True).stdout

def lease():
    seq = int(time.time() * 1000) % 1000000
    conflict = None
    for _ in range(12):
        try:
            data = call("POST", "/v1/lease", headers={"X-Owner-Id": "r21-l4b-%d" % seq})["data"]
            break
        except RuntimeError as fault:
            conflict = fault; time.sleep(5)
    else:
        raise conflict
    json.dump({"token": data["token"]}, open(LEASE_FILE, "w", encoding="utf-8"))
    return data["token"]

def inventory():
    d = call("GET", "/v1/observe", tok)["data"]["observation"]["inventory"]
    return d.get("minecraft:raw_iron", 0)

def opp_status():
    ops = call("GET", "/v1/observe", tok)["data"]["observation"]["semantic_world"]["resource_opportunities"]
    for o in ops:
        if o["id"] == OPP: return o["status"], o.get("blocked_reason", "")
    return None, "GONE"

action = sys.argv[1] if len(sys.argv) > 1 else "pending"

if action == "status":
    tok = lease(); print("raw_iron:", inventory(), "opp:", opp_status())
    call("DELETE", "/v1/lease", tok); sys.exit(0)

if action == "pending":
    tok = lease()
    call("GET", "/v1/observe", tok)
    base = inventory()
    print("raw_iron before:", base)
    ex = call("POST", "/v1/executions/mine_opportunity", tok, json.dumps({"id": OPP}),
              {"X-Request-Id": "r21-l4b-mine-%d" % int(time.time())})["data"]["execution_id"]
    print("execution:", ex)
    stripped = False
    deadline = time.time() + 300
    while time.time() < deadline:
        time.sleep(1)
        if not stripped and "Seed" in str(rcon("execute if block " + ORE + " air run seed")):
            print("  ore broken -> killing item drops:", str(rcon("kill @e[type=item]")).strip()[:50])
            stripped = True
        st = call("GET", "/v1/executions/" + ex, tok)["data"]
        if st["state"] == "failed":
            print("  state=failed reason=%s" % st["reason"])
            break
        if st["state"] == "completed":
            print("  state=completed (drop was collected by the mining task itself)")
            break
    print("opp after:", opp_status(), "| raw_iron:", inventory())
    # stop the bot's own follow-up work so the next phase starts clean
    call("DELETE", "/v1/lease", tok)
    sys.exit(0)

if action == "recover":
    tok = lease()
    print("before recover: raw_iron=%d opp=%s" % (inventory(), opp_status()))
    ex = call("POST", "/v1/executions/mine_opportunity", tok, json.dumps({"id": OPP}),
              {"X-Request-Id": "r21-l4b-recover-%d" % int(time.time())})["data"]["execution_id"]
    print("execution:", ex)
    deadline, renewed = time.time() + 300, time.time()
    while time.time() < deadline:
        time.sleep(2)
        if time.time() - renewed > 15:
            try: call("POST", "/v1/lease/renew", tok)
            except RuntimeError: pass
            renewed = time.time()
        st = call("GET", "/v1/executions/" + ex, tok)["data"]
        if st["state"] in ("completed", "failed", "cancelled", "outcome_unknown"):
            print("TERMINAL:", st["state"], "|", st["reason"])
            break
    print("after recover: raw_iron=%d opp=%s" % (inventory(), opp_status()))
    try: call("DELETE", "/v1/lease", tok)
    except Exception: pass
