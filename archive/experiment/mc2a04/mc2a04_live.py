# MC-2A0.4 LIVE 驱动:bridge HTTP 直调(与上轮 r21_live 同型),本轮 real_client 后端。
import json, os, sys, time, urllib.request, urllib.error

BASE = "http://127.0.0.1:8765"
TOKEN = "O6Pli1LshP4IEZX6YSJfJvUaSyYSvj8oM2TR0NvO"
STATE = "/d/code/mc-experiment/mc2a04-live-state.json".replace("/", os.sep) if os.name == "nt" else "/d/code/mc-experiment/mc2a04-live-state.json"

def call(method, path, lease=None, body=None, headers=None, timeout=20):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    if lease: req.add_header("X-Control-Token", lease)
    for k, v in (headers or {}).items(): req.add_header(k, v)
    data = None
    if body is not None:
        data = json.dumps(body).encode(); req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data, timeout=timeout) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as fault:
        return {"_http": fault.code, "_body": fault.read().decode()[:400]}

def load_lease():
    if os.path.exists(STATE):
        st = json.load(open(STATE, encoding="utf-8"))
        if time.time() - st.get("ts", 0) < 20:
            call("POST", "/v1/lease/renew", st["token"]); st["ts"] = time.time()
            json.dump(st, open(STATE, "w", encoding="utf-8")); return st["token"]
        call("DELETE", "/v1/lease", st["token"])
    d = call("POST", "/v1/lease", headers={"X-Owner-Id": "mc2a04-live-%d" % int(time.time())})["data"]
    json.dump({"token": d["control_token"], "ts": time.time()}, open(STATE, "w", encoding="utf-8"))
    return d["control_token"]

def wait_execution(ex_id, lease, timeout_s=120):
    t0 = time.time()
    while time.time() - t0 < timeout_s:
        d = call("GET", "/v1/executions/" + ex_id, lease)
        st = d.get("data", {}).get("state") or d.get("data", {}).get("status")
        if st in ("SUCCEEDED", "FAILED", "CANCELLED", "OUTCOME_UNKNOWN", "REJECTED", "COMPLETED"):
            return d["data"]
        call("POST", "/v1/lease/renew", lease)
        time.sleep(1.5)
    return {"state": "TIMEOUT"}

if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    if cmd == "status":
        print(json.dumps(call("GET", "/v1/status"), ensure_ascii=False, indent=1))
    elif cmd == "observe":
        lease = load_lease()
        d = call("GET", "/v1/observe", lease)
        print(json.dumps(d, ensure_ascii=False)[:2000])
    elif cmd == "reject_gather":
        lease = load_lease()
        call("GET", "/v1/observe", lease)
        r = call("POST", "/v1/executions/gather", lease,
                 {"arguments": {"item": "minecraft:oak_log", "count": 8}})
        print(json.dumps(r, ensure_ascii=False, indent=1))
