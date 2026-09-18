# -*- coding: utf-8 -*-
"""MC-2A0 live acceptance driver (cognitive view queries + shared lease + exec).

用法:
  python mc2a0_live.py view                       # POST /v1/view,打印 meta+scene 摘要
  python mc2a0_live.py view3 [interval_s]         # 连续 3 次 view:tick 变化 + hash 稳定性对照
  python mc2a0_live.py inspect <ref> [detail]     # POST /v1/inspect?ref=...
  python mc2a0_live.py local [radius] [detail]    # POST /v1/inspect-local?radius=...
  python mc2a0_live.py observe                    # 低层 observe 摘要(reconciliation 用)
  python mc2a0_live.py exec <op> '<json>' [budget_s]
  python mc2a0_live.py raw <method> <path> [json-body]
"""
import json, os, sys, time, urllib.request, urllib.error

TOKEN = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "mc-server-mc1ca", "bridge-token.txt")).read().strip()
BASE = "http://127.0.0.1:8765"
LEASE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".mc2a0-lease.json")

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
                call("POST", "/v1/lease/renew", st["token"]); st["ts"] = time.time()
                json.dump(st, open(LEASE_FILE, "w", encoding="utf-8")); return st["token"]
            except Exception: pass
    import random
    seq = int(time.time() * 1000) + random.randint(0, 999)
    try: data = call("POST", "/v1/lease", headers={"X-Owner-Id": "mc2a0-live-%d" % seq})["data"]
    except urllib.error.HTTPError as conflict:
        print("lease conflict:", conflict); raise
    st = {"token": data["token"], "ts": time.time()}
    json.dump(st, open(LEASE_FILE, "w", encoding="utf-8")); return st["token"]

def view_once():
    d = call("POST", "/v1/view")["data"]
    scene_field = d["scene"]
    scene = scene_field if isinstance(scene_field, dict) else json.loads(scene_field)
    return d, d["meta"], scene

def scene_summary(scene):
    lines = []
    lines.append("world %s %s" % (scene["world"]["world_id"][:8], scene["world"]["dimension"]))
    inv = scene["self"]["inventory"]
    lines.append("self pos=%s inv_items=%d oak_log=%s" % (scene["self"]["block_position"], len(inv), inv.get("minecraft:oak_log", 0)))
    env = scene["environment"]
    lines.append("env %s %s light=%s hostile=%s" % (env["day_phase"], env["weather"], env["local_light"], env["nearby"]["hostile_count"]))
    so = scene["semantic_objects"]
    lines.append("structs=%s farms=%s opps=%s(total) events=%s(%s)" % (
        len(so["structures"]["items"]), len(so["farms"]["items"]),
        len(so["resource_opportunities"]["items"]), len(scene["recent_significant_events"]["items"]),
        scene["recent_significant_events"]["availability"]))
    ex = scene["execution"]
    lines.append("exec %s %s bucket=%s safety=%s paused=%s" % (ex["state"], ex["current_task"], ex["progress_bucket"], ex["safety_active"], ex["user_paused"]))
    if scene["uncertainty"]: lines.append("uncertainty=%d" % len(scene["uncertainty"]))
    return "\n".join(lines)

def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "view"
    if cmd == "viewjson":
        d, meta, scene = view_once()
        print(json.dumps({"meta": meta, "scene": scene}, ensure_ascii=False))
    elif cmd == "view":
        d, meta, scene = view_once()
        print("meta:", json.dumps(meta, ensure_ascii=False))
        print(scene_summary(scene))
        print("scene:", json.dumps(scene, ensure_ascii=False)[:1200])
    elif cmd == "view3":
        interval = float(sys.argv[2]) if len(sys.argv) > 2 else 1.0
        results = []
        for i in range(3):
            d, meta, scene = view_once()
            results.append((meta["generated_server_tick"], meta["scene_hash"], meta["encoded_bytes"]))
            print("view#%d tick=%s hash=%s bytes=%s" % (i + 1, *results[-1]))
            if i < 2: time.sleep(interval)
        ticks_move = results[1][0] > results[0][0] or results[2][0] > results[1][0]
        stable = results[0][1] == results[1][1] == results[2][1]
        print("ticks_move=%s hash_stable=%s" % (ticks_move, stable))
    elif cmd == "inspect":
        ref = sys.argv[2]; detail = sys.argv[3] if len(sys.argv) > 3 else "summary"
        from urllib.parse import quote
        d = call("POST", "/v1/inspect?ref=%s&detail=%s" % (quote(ref, safe=""), detail))["data"]
        print(json.dumps(d, ensure_ascii=False, indent=1)[:2000])
    elif cmd == "local":
        radius = sys.argv[2] if len(sys.argv) > 2 else "4"; detail = sys.argv[3] if len(sys.argv) > 3 else "summary"
        d = call("POST", "/v1/inspect-local?radius=%s&detail=%s" % (radius, detail))["data"]
        print(json.dumps(d, ensure_ascii=False, indent=1)[:60000])
    elif cmd == "observe":
        lease = get_lease()
        d = call("GET", "/v1/observe", lease)["data"]
        obs = d["observation"]
        print("pos:", obs["position"], "dim:", obs["dimension"], "exec:", d["execution"].get("state"))
        print("inventory:", json.dumps(obs.get("inventory"), ensure_ascii=False))
    elif cmd == "exec":
        lease = get_lease()
        try: call("GET", "/v1/observe", lease)
        except urllib.error.HTTPError: pass
        op = sys.argv[2]; args = sys.argv[3]; budget = float(sys.argv[4]) if len(sys.argv) > 4 else 300
        ex = call("POST", "/v1/executions/" + op, lease, args,
                  {"X-Request-Id": "mc2a0-%s-%d" % (op, int(time.time() * 1000))})["data"]["execution_id"]
        print("execution:", ex)
        deadline, renewed = time.time() + budget, time.time()
        while time.time() < deadline:
            if time.time() - renewed > 15: call("POST", "/v1/lease/renew", lease); renewed = time.time()
            st = call("GET", "/v1/executions/" + ex, lease)["data"]
            if st.get("terminal"): print("terminal:", json.dumps(st, ensure_ascii=False)); return
            time.sleep(2)
        print("TIMEOUT still:", json.dumps(st, ensure_ascii=False))
    elif cmd == "raw":
        lease = get_lease() if len(sys.argv) > 4 else None
        print(json.dumps(call(sys.argv[2], sys.argv[3], lease, sys.argv[4] if len(sys.argv) > 4 else None), ensure_ascii=False, indent=1)[:2400])
    else:
        print(__doc__)

if __name__ == "__main__":
    main()
