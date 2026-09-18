# -*- coding: utf-8 -*-
"""R2.1 live acceptance helper: fresh lease -> submit op -> poll to terminal. Usage:
python bridge_exec.py <operation> '<json-args>' [request-id] [poll-seconds]"""
import json, sys, time, urllib.request

TOKEN = "mc1ca-isolated-token-0123456789abcdefghijklmnop"
BASE = "http://127.0.0.1:8765"

def call(method, path, lease=None, body=None, headers_extra=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    if lease: req.add_header("X-Control-Token", lease)
    for k, v in (headers_extra or {}).items(): req.add_header(k, v)
    data = None
    if body is not None:
        data = body.encode("utf-8")
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, data, timeout=15) as r:
        return json.loads(r.read().decode("utf-8"))

def main():
    op, args = sys.argv[1], sys.argv[2]
    rid = sys.argv[3] if len(sys.argv) > 3 else ("r21-" + op + "-" + str(int(time.time())))
    budget = int(sys.argv[4]) if len(sys.argv) > 4 else 120
    lease = call("POST", "/v1/lease", headers_extra={"X-Owner-Id": "r21-live"})["data"]["token"]
    try:
        call("GET", "/v1/observe", lease)  # post-restart reconciliation unlock
    except Exception:
        pass
    submit = call("POST", "/v1/executions/" + op, lease, args,
                  {"X-Request-Id": rid})
    execution = submit["data"]["execution_id"]
    print("execution:", execution)
    deadline = time.time() + budget
    renewed = time.time()
    while time.time() < deadline:
        time.sleep(4)
        if time.time() - renewed > 15:
            call("POST", "/v1/lease/renew", lease); renewed = time.time()
        d = call("GET", "/v1/executions/" + execution, lease)["data"]
        print("  state=%s progress=%.2f reason=%s" % (d["state"], d["progress"], d["reason"][:160]))
        if d["state"] in ("completed", "failed", "cancelled", "outcome_unknown"):
            call("DELETE", "/v1/lease", lease)
            print("TERMINAL", d["state"], "|", d["reason"])
            return 0 if d["state"] == "completed" else 1
    print("TIMEOUT")
    return 2

sys.exit(main())
