# -*- coding: utf-8 -*-
"""MC-2A0.5 LIVE 驱动(bridge HTTP 直调 v3)。"""
import json, os, sys, time, urllib.request, urllib.error
BASE = "http://127.0.0.1:8765"
TOKEN = dict(l.strip().split("=", 1) for l in open(
    r"D:\code\mc-experiment\mc2a05-tokens.env").read().splitlines() if "=" in l)["AIBOT_BRIDGE_TOKEN"]
STATE = r"D:\code\mc-experiment\mc2a05-live-state.json"
def call(method, path, lease=None, body=None, headers=None, timeout=25):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    if lease: req.add_header("X-Control-Token", lease)
    for k, v in (headers or {}).items(): req.add_header(k, v)
    data = json.dumps(body).encode() if body is not None else None
    if data: req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data, timeout=timeout) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as fault:
        return {"_http": fault.code, "_body": fault.read().decode()[:400]}
def req_id(tag): return "mc2a05-%s-%d" % (tag, int(time.time() * 1000) % 100000000)
def observe_for_lease(owner="mc2a05-live", wait_s=45):
    deadline = time.time() + wait_s
    suffix = 0
    while time.time() < deadline:
        call("GET", "/v1/observe")
        if os.path.exists(STATE):
            try:
                st = json.load(open(STATE, encoding="utf-8"))
                if st.get("token"):
                    r = call("POST", "/v1/lease", st["token"], None, {"X-Owner-Id": st.get("owner","x")})
                    if r.get("ok"):
                        tok = r["data"]["token"]
                        if tok != st["token"]:
                            st["token"] = tok
                            json.dump(st, open(STATE, "w"))
                        return tok
            except Exception: pass
        d = call("POST", "/v1/lease", headers={"X-Owner-Id": "%s-%d" % (owner, suffix)})
        if d.get("ok"):
            tok = d["data"]["token"]
            json.dump({"owner": "%s-%d" % (owner, suffix), "token": tok, "ts": time.time()}, open(STATE, "w"))
            return tok
        suffix += 1
        time.sleep(3)
    return None
def submit(lease, op, args, tag):
    return call("POST", "/v1/executions/" + op, lease, args, {"X-Request-Id": req_id(tag)})
def execution(lease, ex_id): return call("GET", "/v1/executions/" + ex_id, lease)
def status(): return call("GET", "/v1/status")
def wait_terminal(lease, ex_id, timeout_s=90):
    t0 = time.time(); last = None; trail = []
    while time.time() - t0 < timeout_s:
        last = execution(lease, ex_id)
        st = (last.get("data") or {}).get("state")
        low = st.lower() if isinstance(st, str) else ""
        trail.append((round(time.time()-t0,2), low, ((last.get("data") or {}).get("reason") or "")[:52]))
        if low in ("completed","failed","cancelled","outcome_unknown","rejected"):
            return last.get("data"), trail
        call("POST", "/v1/lease/renew", lease); time.sleep(0.8)
    return {"state": "TIMEOUT", "last": last}, trail
