# -*- coding: utf-8 -*-
"""MC-2A0.4A LIVE 驱动:bridge HTTP 直调(real_client v2)。token 从私有 env 文件读取。"""
import json, os, sys, time, urllib.request, urllib.error

BASE = "http://127.0.0.1:8765"
TOKEN = dict(l.strip().split("=", 1) for l in open(
    r"D:\code\mc-experiment\mc2a04a-tokens.env").read().splitlines() if "=" in l)["AIBOT_BRIDGE_TOKEN"]
STATE = r"D:\code\mc-experiment\mc2a04a-live-state.json"

def call(method, path, lease=None, body=None, headers=None, timeout=25):
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

def req_id(tag):
    return "mc2a04a-%s-%d" % (tag, int(time.time() * 1000) % 100000000)

def observe_for_lease(owner="mc2a04a-live"):
    call("GET", "/v1/observe")
    if os.path.exists(STATE):
        try:
            st = json.load(open(STATE, encoding="utf-8"))
            if st.get("owner") == owner and st.get("token"):
                r = call("POST", "/v1/lease", st["token"], None, {"X-Owner-Id": owner})
                if r.get("ok"):
                    return st["token"]
        except Exception:
            pass
    d = call("POST", "/v1/lease", headers={"X-Owner-Id": owner})
    if not d.get("ok"):
        return None
    tok = d["data"]["token"]
    json.dump({"owner": owner, "token": tok, "ts": time.time()},
              open(STATE, "w", encoding="utf-8"))
    return tok

def submit(lease, op, args, tag):
    return call("POST", "/v1/executions/" + op, lease, args,
                {"X-Request-Id": req_id(tag)})

def execution(lease, ex_id):
    return call("GET", "/v1/executions/" + ex_id, lease)

def wait_terminal(lease, ex_id, timeout_s=90, quiet=True):
    t0 = time.time(); last = None
    while time.time() - t0 < timeout_s:
        last = execution(lease, ex_id)
        st = (last.get("data") or {}).get("state")
        if st in ("COMPLETED", "FAILED", "CANCELLED", "OUTCOME_UNKNOWN", "REJECTED"):
            return last.get("data")
        call("POST", "/v1/lease/renew", lease)
        time.sleep(1.0)
    return {"state": "TIMEOUT", "last": last}

def status():
    return call("GET", "/v1/status")

if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    if cmd == "status":
        print(json.dumps(status(), ensure_ascii=False, indent=1)[:1200])
    elif cmd == "observe":
        d = call("GET", "/v1/observe")
        print(json.dumps(d, ensure_ascii=False)[:2400])
