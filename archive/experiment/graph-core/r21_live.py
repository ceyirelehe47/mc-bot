# -*- coding: utf-8 -*-
"""R2.1 live acceptance driver. Shared lease file + exec + poll + observe digests.

用法:
  python r21_live.py observe                 # 摘要观察(world/structures/farms/opportunities/inventory)
  python r21_live.py exec <op> '<json>' [budget_s]   # 提交并轮询到终态
  python r21_live.py raw <method> <path> [json-body] # 原始调用
"""
import json, os, sys, time, urllib.request, urllib.error

TOKEN = "mc1ca-isolated-token-0123456789abcdefghijklmnop"
BASE = "http://127.0.0.1:8765"
LEASE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".r21-lease.json")

def call(method, path, lease=None, body=None, headers=None, timeout=20):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    if lease: req.add_header("X-Control-Token", lease)
    for k, v in (headers or {}).items(): req.add_header(k, v)
    data = None
    if body is not None:
        data = body.encode(); req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data, timeout=timeout) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as fault:
        raise RuntimeError('%s %s -> %s %s' % (method, path, fault.code, fault.read().decode()[:200]))

def get_lease():
    if os.path.exists(LEASE_FILE):
        st = json.load(open(LEASE_FILE, encoding="utf-8"))
        if time.time() - st["ts"] < 20:
            try:
                call("POST", "/v1/lease/renew", st["token"])
                st["ts"] = time.time()
                json.dump(st, open(LEASE_FILE, "w", encoding="utf-8"))
                return st["token"]
            except Exception:
                pass  # stale/foreign lease: fall through to a fresh claim
    import random
    seq = int(time.time() * 1000) + random.randint(0, 999)
    conflict = None
    for attempt in range(12):  # a stale lease holds control for its 30s TTL: wait it out
        try:
            data = call("POST", "/v1/lease", headers={"X-Owner-Id": "r21-live-%d" % seq})["data"]
            break
        except RuntimeError as fault:
            conflict = fault
            time.sleep(5)
    else:
        raise conflict
    st = {"token": data["token"], "ts": time.time()}
    json.dump(st, open(LEASE_FILE, "w", encoding="utf-8"))
    return st["token"]

def observe_digest():
    lease = get_lease()
    d = call("GET", "/v1/observe", lease)["data"]
    obs = d["observation"]
    sem = obs["semantic_world"]
    print("pos:", obs["position"], "dim:", obs["dimension"])
    inv = obs.get("inventory")
    if inv: print("inventory:", json.dumps(inv, ensure_ascii=False))
    for s in sem["structures"]:
        print("structure %s: integrity=%s missing=%s wrong=%s repairable=%s" % (
            s["id"], s.get("integrity"), s.get("integrity_missing"), s.get("integrity_wrong"), s.get("repairable")))
    for f in sem["farms"]:
        print("farm %s: cells=%s mature=%s empty=%s" % (f["id"], f.get("registered_cells"), f.get("mature"), f.get("empty_farmland")))
    for o in sem["resource_opportunities"]:
        print("opp %s %s %s dist=%s" % (o["block"], o["status"], o.get("blocked_reason"), o["distance"]))
    return d

def exec_op(op, args, budget):
    lease = get_lease()
    try:
        call("GET", "/v1/observe", lease)
    except urllib.error.HTTPError:
        pass
    ex = call("POST", "/v1/executions/" + op, lease, args,
              {"X-Request-Id": "r21-%s-%d" % (op, int(time.time()))})["data"]["execution_id"]
    print("execution:", ex)
    deadline, renewed = time.time() + budget, time.time()
    while time.time() < deadline:
        time.sleep(4)
        if time.time() - renewed > 15:
            try: call("POST", "/v1/lease/renew", lease)
            except urllib.error.HTTPError: lease = get_lease()
            renewed = time.time()
        d = call("GET", "/v1/executions/" + ex, lease)["data"]
        print("  state=%s p=%.2f reason=%s" % (d["state"], d["progress"], d["reason"][:200]))
        if d["state"] in ("completed", "failed", "cancelled", "outcome_unknown"):
            print("TERMINAL:", d["state"], "|", d["reason"])
            return 0 if d["state"] == "completed" else 1
    print("TIMEOUT"); return 2

def main():
    cmd = sys.argv[1]
    if cmd == "observe": observe_digest()
    elif cmd == "exec":
        sys.exit(exec_op(sys.argv[2], sys.argv[3], int(sys.argv[4]) if len(sys.argv) > 4 else 120))
    elif cmd == "raw":
        lease = get_lease()
        try:
            print(json.dumps(call(sys.argv[2], sys.argv[3], lease,
                                  sys.argv[4] if len(sys.argv) > 4 else None), ensure_ascii=False))
        finally:
            try: call("DELETE", "/v1/lease", lease)
            except Exception: pass
main()
