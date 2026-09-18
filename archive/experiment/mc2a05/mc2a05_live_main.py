# -*- coding: utf-8 -*-
"""MC-2A0.5 LIVE 主驱动:单进程持有 lease,顺序完成 B/C/D 交互,证据落盘。"""
import importlib.util, json, time, urllib.request, urllib.error

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
    return m

lv = load("lv", r"D:\code\mc-experiment\mc2a05_live.py")
rm = load("rcon_mod", r"D:\code\mc-experiment\rcon.py")

def rc(c):
    return rm.rcon(c, port=25575, pwd='mc2a04a-rcon-local')

def poll(ex, seconds=12, step=0.4):
    t0 = time.time(); d = {}
    while time.time() - t0 < seconds:
        d = lv.execution(LEASE, ex)["data"]
        if str(d.get("state", "")).lower() in ("completed", "failed", "cancelled", "outcome_unknown"):
            break
        lv.call("POST", "/v1/lease/renew", LEASE)
        time.sleep(step)
    return d

EVID = r"D:\code\mc-experiment\mc2a05-evidence"
import os
os.makedirs(EVID, exist_ok=True)
def save(name, obj):
    json.dump(obj, open(os.path.join(EVID, name), "w"), ensure_ascii=False, indent=1)

LEASE = None

def main():
    global LEASE
    LEASE = lv.observe_for_lease("live-main", wait_s=60)
    print("[lease]", bool(LEASE)); assert LEASE
    out = {}

    # ===== LIVE-B:站桩 final-facing(基线) =====
    rc("tp Bob 200.5 -60 200.5"); time.sleep(1.2)
    r = lv.submit(LEASE, "goto", {"x": 200, "y": -60, "z": 200,
        "face_x": 204, "face_y": -60, "face_z": 200}, "liveB-face6")
    print("[face6 submit]", r.get("ok"))
    d = poll(r["data"]["execution_id"], 10)
    out["face6"] = {"state": d.get("state"), "reason": d.get("reason"),
                    "rot_post": rc("data get entity Bob Rotation")[24:70]}
    print("[face6]", out["face6"])

    # ===== LIVE-C/D:barrel deposit 垂直切片 =====
    # 站桩面向 barrel(200,-60,196):北
    rc("tp Bob 200.5 -60 200.5"); time.sleep(1.2)
    r = lv.submit(LEASE, "goto", {"x": 200, "y": -60, "z": 200,
        "face_x": 200, "face_y": -60, "face_z": 196}, "liveC-face-barrel")
    d = poll(r["data"]["execution_id"], 12)
    out["face_barrel"] = {"state": d.get("state"), "reason": d.get("reason"),
                          "rot": rc("data get entity Bob Rotation")[24:70]}
    print("[face_barrel]", out["face_barrel"])
    time.sleep(3)  # 等 fresh sensor

    # deposit 前基线
    out["inv_before"] = rc("data get entity Bob Inventory")[24:150]
    out["barrel_before"] = rc("data get block 200 -60 196 Items")[10:150] if "Items" in rc("data get block 200 -60 196 Items") else "empty"
    print("[before] inv:", out["inv_before"][:80])
    print("[before] barrel:", out["barrel_before"][:80])

    # deposit(现有 operation,非新工具)
    r = lv.submit(LEASE, "deposit", {"x": 200, "y": -60, "z": 196}, "liveD-deposit")
    print("[deposit submit]", json.dumps(r, ensure_ascii=False)[:160])
    if r.get("ok"):
        d = poll(r["data"]["execution_id"], 45, 0.5)
        out["deposit"] = {"state": d.get("state"), "reason": (d.get("reason") or "")[:80]}
        print("[deposit]", out["deposit"])
    else:
        out["deposit"] = {"submit_raw": r}

    out["inv_after"] = rc("data get entity Bob Inventory")[24:160]
    out["barrel_after"] = rc("data get block 200 -60 196 Items")[10:180]
    print("[after] inv:", out["inv_after"][:100])
    print("[after] barrel:", out["barrel_after"][:100])

    # Screen 快照期间 mc_view(若 deposit 完成太快,view 读取 ui 字段快照)
    view = lv.call("POST", "/v1/view", LEASE, {"detail": "standard"})
    out["view_ui"] = json.dumps(view, ensure_ascii=False)[:1200]
    print("[view ui snippet]", out["view_ui"][:400])

    save("live-BCD-main.json", out)

if __name__ == "__main__":
    main()
