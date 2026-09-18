# -*- coding: utf-8 -*-
"""MC-2A0.1 live acceptance driver (cognitive evidence boundary).

用法:
  python mc2a01_live.py view                 # 认知视图(scene 结构卡/execution)
  python mc2a01_live.py inspect <ref> [detail]
  python mc2a01_live.py local [radius] [detail]
  python mc2a01_live.py raw <method> <path> [json-body]
  python mc2a01_live.py burst [n]            # LIVE-2A01-4: 并发 n 个 radius=8 local query, 打印完成时间
"""
import json, os, sys, time, urllib.request, urllib.error, urllib.parse
import concurrent.futures

TOKEN = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "mc-server-mc1ca", "bridge-token.txt")).read().strip()
BASE = "http://127.0.0.1:8765"


def call(method, path, body=None, timeout=30):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    data = None
    if body is not None:
        data = body.encode()
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data, timeout=timeout) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as fault:
        try:
            detail = json.loads(fault.read().decode())
        except Exception:
            detail = {}
        raise RuntimeError("%s %s -> %s %s" % (method, path, fault.code, detail.get("error", "")))


def pp(label, obj):
    print(label + ": " + json.dumps(obj, ensure_ascii=False, sort_keys=True))


def structure_cards(view):
    items = view["data"]["scene"]["semantic_objects"]["structures"]["items"]
    return {c["object_id"]: c for c in items}


def cmd_view():
    v = call("POST", "/v1/view")
    meta = v["data"]["meta"]
    print("meta:", json.dumps(meta, sort_keys=True))
    cards = structure_cards(v)
    for oid, c in cards.items():
        print("struct %s: knowledge=%s freshness=%s summary=%s" % (
            oid, c["knowledge"], c["freshness"], json.dumps(c["summary"], sort_keys=True)))
    ex = v["data"]["scene"]["execution"]
    print("execution:", json.dumps(ex, sort_keys=True))
    opps = v["data"]["scene"]["semantic_objects"]["resource_opportunities"]
    print("opportunity total:", opps.get("total"), "first freshness:",
          opps["items"][0].get("freshness") if opps.get("items") else "-")
    return v


def cmd_inspect(ref, detail):
    q = urllib.parse.urlencode({"ref": ref, "detail": detail or "summary"})
    r = call("POST", "/v1/inspect?" + q)
    print("detail=" + r["data"]["detail"] + " meta=" + json.dumps(r["data"]["meta"], sort_keys=True))
    pp("evidence", r["data"]["evidence"])


def cmd_local(radius=8, detail="blocks"):
    q = urllib.parse.urlencode({"radius": radius, "detail": detail})
    t0 = time.time()
    r = call("POST", "/v1/inspect-local?" + q, timeout=30)
    ms = (time.time() - t0) * 1000
    snap = r["data"]["snapshot"]
    print("local %.1fms visible=%s radius_effective=%s entities=%s" % (
        ms, snap["blocks"]["visible_blocks"], snap["radius_effective"], snap["entities"]["visible_entities"]))
    return ms


def cmd_burst(n=4):
    """LIVE-2A01-4: n 个并发 radius=8 local query,观察完成时间是否分散在多个 tick。"""
    def one(i):
        q = urllib.parse.urlencode({"radius": 8, "detail": "blocks"})
        t0 = time.time()
        r = call("POST", "/v1/inspect-local?" + q, timeout=30)
        return (time.time() - t0) * 1000

    t0 = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=n) as pool:
        results = list(pool.map(one, range(n)))
    total = (time.time() - t0) * 1000
    print("burst n=%d completion_ms=%s wall=%.1fms spread=%.1fms" % (
        n, ["%.0f" % m for m in results], total, max(results) - min(results)))
    return results


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    cmd = sys.argv[1]
    if cmd == "view":
        cmd_view()
    elif cmd == "inspect":
        cmd_inspect(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
    elif cmd == "local":
        cmd_local(int(sys.argv[2]) if len(sys.argv) > 2 else 8, sys.argv[3] if len(sys.argv) > 3 else "blocks")
    elif cmd == "burst":
        cmd_burst(int(sys.argv[2]) if len(sys.argv) > 2 else 4)
    elif cmd == "raw":
        print(json.dumps(call(sys.argv[2], sys.argv[3], sys.argv[4] if len(sys.argv) > 4 else None), ensure_ascii=False, indent=1))
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
